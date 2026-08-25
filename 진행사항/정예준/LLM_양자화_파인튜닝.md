## skeleton01 — Teacher LLM으로 학습 데이터 만들기

### 한 것
- `.env` + `python-dotenv`로 `UPSTAGE_API_KEY` 로드 → OpenAI SDK를 `base_url="https://api.upstage.ai/v1"`로 재사용
- Spider 계열 `data/train.csv` (4,471행, `db_id` + `question`, **정답 SQL 없음**) 로드
- 상위 10건에 대해 `solar-pro3` + `reasoning_effort="high"`로 SQL 생성 → `labeled_data_with_judge.json` 저장

### 핵심 개념 정리
| 개념 | 요점 |
|---|---|
| KD 분류 | Response-based(logit/pseudo-label) / Feature-based(hint, attention transfer) / Relation-based(distance, graph) |
| Black-box KD | API Teacher는 logit 접근 불가 → **Hard Target(Pseudo Labeling)** 이 유일한 현실적 선택 |
| CoT Distillation | `message.reasoning`(사고 과정)까지 데이터에 담아 Student의 논리력을 끌어올리는 전략 |
| structured output | 프롬프트에 JSON 형식 명시 + 파싱 함수 vs `response_format` json_schema(`strict: True`) |
| LLM as a Judge | `score` + `critique` 동시 생성, `seed=42` / `temperature=0`으로 재현성 확보 |

### 관찰한 결과
- `response_format`(json_schema) 방식은 코드펜스 없이 순수 JSON 문자열을 반환 → `json.loads()` 바로 통과.
  프롬프트로만 형식을 지시한 경우는 json 코드블록이 붙어 별도 파싱 함수(`json_parsing`)가 필요했다.
- reasoning 스트림 확인: `delta.reasoning`(사고) / `delta.content`(답변)이 분리되어 나온다.
- **스키마를 안 준 게 실질적 결함**: GENERATOR 프롬프트에 few-shot 예시만 있고 실제 DDL이 없어서,
  reasoning 로그가 전부 `"schema is not provided... we need to infer typical naming"`으로 시작한다.
  모델이 `head`, `Age` 같은 컬럼명을 **추측**해서 맞춘 것.
- 그런데 Judge는 이 샘플에 **score 5**를 줬다. "스키마 관례와 일치한다"는 이유로 후하게 평가한 것 →
  노트북 본문의 *"LLM as a Judge는 판단 기준으로만 쓰고 사람 검수가 필요하다"* 는 서술이 실측으로 증명된 셈.
  SQL처럼 정답군이 존재하는 태스크는 **execution 기반 평가가 Judge보다 강하다**.

### 배운 것
- 정답 없는 데이터 + 예산 제약 = 큰 모델을 API로 빌려 라벨을 찍는 게 가장 싼 길.
- 단, **입력 컨텍스트(스키마)를 빠뜨리면 Teacher의 품질도 추측 수준으로 떨어진다.** 프롬프트에 스키마를 주입하는 게 다음 개선점.

---

## skeleton02 — LoRA fine-tuning (SmolLM2-360M)

### 환경 / 설정
- 모델: `HuggingFaceTB/SmolLM2-360M-Instruct`, `dtype=bfloat16`, `device_map={"":"cuda:0"}`
- 데이터: train 7,000행 / db_id 140종, test(validation) — `query`, `question` 2컬럼만 사용
- LoRA: `r=32`, `lora_alpha=64`, `dropout=0.0`, target = `q,k,v,o,gate,up,down_proj` (7개 모듈)
- SFTConfig: `max_steps=100`, `per_device_train_batch_size=4`, `grad_accum=4`, `lr=2e-5`, `bf16=True`, `optim=adamw_torch`

### EDA에서 확인한 것
| 항목 | 값 |
|---|---|
| 문자 길이 max (query / question) | 577 / 224 |
| **토큰** 길이 max (query / question) | **251 / 62** |
| 모델 `max_position_embeddings` | 8192 |

→ 251+62 ≪ 8192, 길이 여유 충분. **"모델이 정해지기 전엔 문자 길이로 어림잡고, 정해진 뒤 토큰으로 재측정"** 순서가 포인트.

### 전처리의 핵심 — labels 마스킹
`input_ids`는 `prompt + <|im_start|>assistant + label + EOS`,
`labels`는 앞부분 전체를 `-100`으로 채우고 **assistant 구간 + EOS만 남긴다.**
`-100`은 CrossEntropy의 `ignore_index`라서 지시문 토큰의 loss가 0이 된다 →
모델이 "질문을 외우는" 대신 **정답 SQL 생성만 학습**한다. (`attention_mask`는 패딩 무시용으로 역할이 다름)

### 실행 결과
| 지표 | 값 |
|---|---|
| Base model execution accuracy (n=60) | **0.567** (easy 0.682 / medium **0.250**) |
| 학습 시간 (100 steps) | **155.6s** (0.643 steps/s, 10.29 samples/s) |
| training_loss (평균) | 1.5657 |
| loss 추이 (step 1 → 100) | 2.331 → 1.159 |
| 학습 후 추론 속도 | 6.28 tokens/sec (105 in / 23 out, 3.66s) |
| 총 FLOPs | 4.94e14 |
| VRAM (모델 로드 시) | 817MiB / 6141MiB |

### 눈에 걸린 것 (중요)
1. **`epoch: 4.0`이 찍혔다.** `train_dataset.select(range(max_steps * per_device_train_batch_size))` =
   400 샘플만 골랐는데, effective batch는 `4 × grad_accum 4 = 16`.
   400/16 = 25 steps/epoch → 100 steps = **같은 400건을 4번 반복**한 것.
   `select()` 계산에 `gradient_accumulation_steps`가 빠져 있다. 의도가 1 epoch였다면
   `max_steps * batch_size * grad_accum = 1600`이어야 한다.
2. **medium 난이도 정확도가 0.250으로 급락** (easy 0.682). JOIN·서브쿼리 구간이 취약점.
   그런데 학습 후 샘플 추론에서도 정답 `JOIN culture_company` 대신
   `SELECT company_name FROM movie WHERE year = 1999 AND director = 'Company Name'`을 냈다 →
   **스키마를 모르는 상태로는 JOIN을 못 만든다**는 게 구조적 한계로 다시 확인됨.
3. 최종 LoRA 성능 수치는 노트북에 없다. `inference.py` → `evaluation.py`는 커널 재시작 후 별도 실행하는 흐름이라
   **fine-tuned exec accuracy는 아직 미측정**.

---

## skeleton03 — QLoRA (SmolLM2-1.7B, 4-bit)

### 설정
```python
BitsAndBytesConfig(
    load_in_4bit=True,
    bnb_4bit_use_double_quant=True,   # 양자화 상수도 재양자화 → 파라미터당 ~0.4bit 추가 절약
    bnb_4bit_quant_type="nf4",        # 가중치가 정규분포를 따른다는 QLoRA 논문 근거
    bnb_4bit_compute_dtype=torch.bfloat16,  # 저장은 4bit, 연산은 dequantize해서 bf16
)
```
LoRA: `r=16`, `alpha=32` (skeleton02보다 절반) / 나머지 SFTConfig는 `warmup_ratio` 0.01→0.03 외 동일.

### 실행 결과 & skeleton02 대비
| 지표 | skeleton02 (360M, bf16 LoRA) | skeleton03 (1.7B, nf4 QLoRA) |
|---|---|---|
| 모델 크기 | 0.36B | **1.7B (약 4.7배)** |
| 로드 후 VRAM | 817MiB | **1115MiB** |
| Base exec accuracy | 0.567 (easy .682 / med .250) | **0.550** (easy .682 / med **.188**) |
| 학습 시간 (100 steps) | 155.6s | **184.1s (1.19배)** |
| steps/sec | 0.643 | 0.542 |
| 총 FLOPs | 4.94e14 | **2.42e15 (4.9배)** |
| training_loss | 1.5657 | **1.5366** |
| 추론 속도 | 6.28 tok/s | 5.79 tok/s |

### 여기서 얻은 인사이트
- **핵심 성과: 파라미터 4.7배 / 연산량 4.9배인 모델을 VRAM 300MiB 추가로 학습했다.**
  4-bit라고 정확히 1/4이 되지 않는 이유는 Norm layer·softmax 등 민감한 레이어를 4bit로 내리지 않기 때문.
- 시간 오버헤드는 **1.19배**. 노트북 본문은 "2분 vs 6분(약 3배)"이라고 서술하지만 **실측은 155.8s vs 184.6s**로
  훨씬 완만했다. 서술을 그대로 믿지 말고 실측값을 기준으로 삼아야 함. (dequantize 오버헤드가 FLOPs 4.9배 증가를
  거의 상쇄한 셈이라 오히려 효율이 좋게 나온 케이스)
- **1.7B 4-bit base가 360M bf16 base보다 exec accuracy가 낮았다** (0.550 vs 0.567, medium 0.188 vs 0.250).
  n=60의 작은 표본이라 단정할 수 없지만, "모델을 키우면 무조건 낫다"가 성립하지 않는 지점.
  양자화 손실 + 스키마 미제공이라는 두 요인이 겹친 것으로 보인다.
- **경고 하나 남았음**: `SFTTrainer` 생성 시
  `UserWarning: You are trying to modify a model with PEFT for a second time` /
  `Already found a peft_config attribute in the model`.
  셀을 재실행해 adapter가 중첩 적용된 상태. 재현성 확보하려면 커널 재시작 또는 `.unload()` 후 학습해야 한다.

---

## skeleton04 — vLLM 서빙 & 벤치마크

> 이 노트북만 실행 환경이 다르다: **Colab Tesla T4 (15360MiB), vllm 0.8.5.post1, torch 2.6.0+cu124**.
> (Prerequisites에는 `vllm==0.13.0`이라 적혀 있으나 실제 설치·실행은 `0.8.5.post1`)
> T4는 Compute Capability < 8.0이라 **V1 Engine 미지원 → V0로 fallback**, `dtype="half"`(fp16) 강제, `enforce_eager=True`.

### 시뮬레이터로 체득한 원리
1. **PagedAttention** — `BLOCK_SIZE=4`, free list를 셔플해서 물리 블록을 배정.
   토큰 8개 저장 시 Block Table이 `[4, 0]`으로 잡혔다 → 논리적으로 연속(`is`→`very`)이어도
   물리 주소는 불연속. 단편화된 VRAM 조각을 100% 쓸 수 있는 이유.
   주소 변환은 `idx // block_size`, `idx % block_size`로 **O(1)**.
   블록은 **꽉 찼을 때만** 새로 할당(on-demand) → 예약 낭비 제거.
2. **KV Cache 연산량** — 4토큰 프롬프트 + 5스텝 생성:
   no-cache **60회** vs with-cache **39회** (1.5배). 스텝 수가 커지면 O(N²) vs O(N)으로 격차가 벌어진다.

### 엔진 초기화 실측
`gpu_memory_utilization=0.75` → `14.56GiB × 0.75 = 10.92GiB` 선점.
그 중 model weights 0.68GiB + activation peak 0.47GiB를 빼고 **KV Cache에 9.73GiB** 배정
→ cuda blocks **15,943**개, 8192 토큰 요청 기준 **최대 동시성 31.14x**.
모델 로딩 총 35.21초 (가중치 로드 자체는 0.85초, 엔진 프로파일링·KV cache 생성·warmup이 4.39초).

### Transformers vs vLLM 벤치마크 (프롬프트 5개, max_tokens=64, merged model)
| 지표 | Transformers | vLLM | 차이 |
|---|---|---|---|
| 총 추론 시간 | 4.84s | **0.44s** | **11.01x 빠름** |
| Throughput | 23.74 tok/s | **302.31 tok/s** | **12.74x** |
| First Token Latency | **64.26ms** | 184.77ms | vLLM이 187.6% 느림 |
| Peak GPU Memory | 12,466MB | 12,508MB | vLLM이 42MB 더 씀 |

**해석 (수치를 그대로 믿으면 안 되는 두 지점)**
- **First token이 vLLM에서 더 느린 건 측정 방식 탓이다.** Transformers 쪽은 순수 forward 1회를 5번 평균낸 값이고,
  vLLM 쪽은 `generate()` 첫 호출 전체(스케줄러 진입 + 첫 요청 warmup 포함)를 1회 측정했다. 공정한 비교가 아니다.
- **메모리가 비슷한 것도 당연하다.** vLLM은 `gpu_memory_utilization` 비율만큼 **미리 선점**하므로
  peak memory는 "실제 필요량"이 아니라 "설정값"을 반영한다. 즉 이 표에서 메모리 비교는 의미가 없고,
  진짜 이득은 **선점한 공간 안에 KV Cache를 얼마나 촘촘히 넣어 동시성을 확보하는가**(= 31.14x)에 있다.
- 반대로 **throughput 12.74x는 신뢰할 수 있는 결과**다. Continuous batching으로 5개 요청을 한 번에 흘린 효과.

### LoRA 통합 두 방식
| 방식 | 코드 | 특징 | 실측 |
|---|---|---|---|
| Runtime LoRA | `enable_lora=True, max_lora_rank=64` + `LoRARequest("my_adapter", 1, path)` | 요청 단위 adapter 교체(Multi-LoRA / adapter routing) | 정상 적용, `SELECT username, age FROM users WHERE age > 30` 생성 |
| Merged Model | `PeftModel.from_pretrained().merge_and_unload()` → `save_pretrained()` | 추론 빠름, 배포 유리. `enforce_eager` 없이 CUDA Graph 캡처(31초, 0.19GiB) | 벤치마크에 사용 |

- skeleton02의 `outputs/checkpoint-*`를 상위 폴더에서 자동 탐색해 adapter로 로드하는 코드까지 확인 (`_find_latest_checkpoint`).
- OpenAI 호환 서버: `vllm serve <model> --enable-lora --max-lora-rank 64 --lora-modules skeleton02=<path>`

### 종합 실습 결과 (fine-tuned adapter로 4문항)
| 질문 | 생성 SQL | 판정 |
|---|---|---|
| How many different types of pet are there? | `SELECT COUNT(*) FROM pets;` | ✗ `COUNT(DISTINCT pettype)`이어야 함 |
| Count employees in each department | 3-table JOIN + `GROUP BY E.name, S.name` | ✗ 불필요한 Salary JOIN, 그룹 기준 오류 |
| How many airlines do we have? | `SELECT COUNT(*) FROM airlines;` | ✓ |
| List departments with budget over 100000 | `SELECT department FROM table_name WHERE budget > 100000` | ✗ `table_name` 플레이스홀더 누출 |

→ **서빙 성능(12.7x)은 확보했지만 모델 품질은 여전히 미달.** 100 step / 400 샘플 / 스키마 미주입의 당연한 귀결.
`table_name` 같은 플레이스홀더가 나오는 건 스키마 정보가 프롬프트에 없다는 가장 직접적인 증거다.

---

## 트러블슈팅

| 증상 | 원인 | 해결 |
|---|---|---|
| `AttributeError: 'BatchEncoding' object has no attribute 'squeeze'` | `apply_chat_template(tokenize=True, return_tensors="pt")`는 텐서가 아니라 dict-like `BatchEncoding`을 반환. `tokenizer.encode()`와 반환 타입이 다르다 | `.input_ids.squeeze(0)`으로 텐서에 먼저 접근 |
| `NameError: name 'convert_train_data' is not defined` | `datasets.map(num_proc=1)`이 멀티프로세싱 풀을 띄우고, 노트북 셀에 정의한 함수는 pickle 직렬화가 안 돼 자식 프로세스로 전달 실패 | `num_proc` 인자 제거(기본 None) → 메인 프로세스 실행 |
| `warmup_ratio is deprecated` (transformers v5.2 제거 예정) | API 변경 | `warmup_steps` 사용 |
| `torch_dtype is deprecated` | API 변경 | `dtype=` 사용 |
| `PEFT for a second time` / `Already found a peft_config` | 셀 재실행으로 adapter 중첩 | 커널 재시작 또는 `.unload()` 후 재학습 |
| 평가 스크립트 경로 문제 | PowerShell과 git bash의 `&&`/`;` 문법 차이 | PowerShell은 `!powershell "cd dir ; python ..."`, bash는 `cd dir && python ...` |
| Colab: V1 Engine 미지원, bf16 캐스팅 경고 | T4는 Compute Capability 7.5 | `dtype="half"` + `enforce_eager=True`로 V0 사용 |
| 데이터셋 삭제 시 잔여물 | nltk 데이터 캐시 | `C:\Users\SSAFY\AppData\Roaming\nltk_data` 확인 |
