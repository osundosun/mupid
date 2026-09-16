# MERT 임베딩 레시피 — `moments_emb.npy` 를 만든 설정

이 문서가 설명하는 판:

```
moments_emb.npy   65,540 × 1536 float32
sha256            f1b0fba1dd10297075d4d6424e196cebb0e1e8d04cb3c004f837cb07aeaef32b
moment_zstats.npz sha256 4488afef924f24ca0f87b7380d1ca029f3bf210a4d1761621c1e3d6fea3debc2
embedding_version mert-v1-95m
```

이 sha256 이 다르면 이 문서는 그 파일을 설명하지 않는다.

## 1. 설정 (원본에서 그대로 옮김)

원본은 GPU 박스의 두 곳이고 **같은 규약**이다.

| 경로 | 무엇을 만드는가 |
|---|---|
| `~/rec_mupid/src/s24_mert.py` | FMA 대량 생성(`mert_shard*.npz`) — 55,232구간 |
| `~/rec_mupid/src/m14_local_tracks.py` 의 `class Mert` | 신곡 편입(flow·jamendo·local) — 나머지 |

```
모델          m-a-p/MERT-v1-95M   (AutoModel.from_pretrained, trust_remote_code=True)
리비전        코드에 박혀 있지 않다. GPU 박스 허브 캐시가 가리키는 값은
              12af15fef9d0ac838c3f475bfbbf26d2060dd4f5  (refs/main)
층 선택       LAYER = "mean"
              torch.stack(o.hidden_states, 0).mean(0)
              → 13벌(임베딩 + 12층) 전체의 평균. 특정 층을 고르지 않는다
시간축 풀링   구간별 concat(blk.mean(0), blk.std(0))
              → 768 + 768 = 1536
입력          SR 24000 · mono · float32 파형
              librosa.load(path, sr=24000, mono=True)
청크          CHUNK = 20 * SR (20초)씩 forward, 이어 붙인다
              꼬리가 1초 미만이면 1초까지 0 패딩
프레임레이트  HZ = 75.0 (24kHz 기준 MERT-v1)
구간 자르기   a = round(start * HZ), b = round(end * HZ)
              b = min(max(b, a+1), len(H)),  a = min(a, len(H)-1)
              **3초 미만 구간은 버린다** (MIN_SEC_S = 3.0)
dtype         GPU 일 때 model.half(), 입력도 float16
              CPU 일 때 float32 (`Mert` 가 device 로 갈라 쓴다)
디바이스      cuda:0 (없으면 cpu)
정규화        Zn = (V - mu) / sd          ← moment_zstats.npz
              Zn /= ||Zn||                ← 1536차 전체를 행 단위 L2
```

## 2. 1536차의 구성 — A·B 는 무엇인가

**서로 다른 층이 아니다.** 같은 (층 평균) 표현의 **시간축 두 통계**다.

```
앞 768 = H[a:b].mean(0)     구간 동안의 평균
뒤 768 = H[a:b].std(0)      구간 동안의 표준편차
```

`H` 는 이미 13개 hidden_state 를 평균 낸 것이라, 층은 하나로 합쳐진 뒤 시간축에서
둘로 갈린다.

### 관찰과의 대조

저장본을 뜯어 얻은 제약이 전부 이 레시피와 맞는다.

| 관찰 | 설명 |
|---|---|
| 행 L2 = 1.0000 ± 0.0000 | 마지막에 1536차 전체를 행 단위 L2 — 맞다 |
| `‖앞‖/‖뒤‖` 평균 1.166, 표준편차 0.283 | 절반씩 따로 정규화하지 않았다 — 맞다 |
| 앞뒤 차원별 상관 중앙값 0.058 | mean 과 std 는 서로 다른 통계라 무관하다 — 맞다 |
| 차원별 평균이 0 이 아니다 | corpus 중심화를 하지 않았다 — 맞다(`mu` 는 z-score 용이고 그 뒤 L2 가 중심을 다시 흐트러뜨린다) |

**"뒤 768 음수 비율 0.413 이니 std 풀링이 아니다" 는 성립하지 않는다.** 저장본은 원값이
아니라 `(V − mu) / sd` 를 거친 값이다. `moment_zstats.npz` 의 `mu` 는 뒤 768 에서
**768개 전부 양수**(범위 0.057 ~ 0.823)이므로, 평균보다 작은 구간은 음수가 된다. 실측
음수 비율 0.449 는 이것으로 설명된다.

## 3. 재현 확인

원본 `Mert` 를 그대로 태워 저장된 행과 대조했다. `moments_emb.npy` 는 읽기만 했다.

| | 값 |
|---|---|
| 대상 | flow 10곡 · **84구간** |
| 원소별 최대 절대오차 | **1.490e-08** |
| 원소별 오차 중앙값 | **0.000e+00** (절반 이상이 비트 단위로 동일) |
| 코사인 최소 | 0.99999988 |

구간 경계·라벨도 저장본과 일치했고(8/8), CPU 로 돌려도 같은 값이 나왔다.

## 4. 합격 기준 (다음 작업이 쓸 것)

재현 판정을 임베딩 하나로 하지 않는다. 비교 대상은 **산출물 파일**이다 — 다른 환경에서
새로 뽑은 값끼리 비교하지 않는다. 모델이 학습된 공간은 저 파일이다.

| 단계 | 기준 | 비교 대상 | 실측 |
|---|---|---|---|
| 임베딩 | **max abs diff < 1e-6** | `moments_emb.npy` 해당 행 | 1.49e-08 |
| 285차 특징 | < 1e-5 | `features_v1.npz` 해당 행 | 1.79e-07 |
| 최종 | `rank_order` 완전 일치 | 같은 사용자·같은 질의의 추천 | 미측정 |

임베딩 기준을 실측(1.49e-08)보다 두 자릿수 넉넉히 잡은 것은 **fp16 추론이라 회차마다
마지막 자리가 흔들릴 수 있기** 때문이다. 그보다 크게 벌어지면 설정이 다른 것이지 수치
오차가 아니다.

## 5. `embedding_version` 과 색인의 관계

`embedding_version: "mert-v1-95m"` 은 **이 문서의 1번 설정 전체**를 가리킨다. 모델·층
선택·풀링·샘플레이트·청크·패딩 중 **하나라도 바뀌면 이 문자열도 바뀌어야 한다.**

바뀌면 `moments_emb.npy` 가 달라지고, 그러면 그 위에 세워진 것이 전부 무효다.

```
moments_emb.npy  →  features_v1.npz (PCA 기저가 이 분포에 적합돼 있다)
                 →  stage1_section.npy
                 →  stage1_weights.npz (in_dim 285)
```

즉 **색인 4종을 다시 만들어야 하고**, `feature_stats_v1.npz` 도 다시 뽑아야 한다
(`FEATURE-SPACE.md` 참조). 한 파일만 갈아 끼우면 엉뚱한 곡을 추천하면서 **정상 기동한다.**

## 6. 코드로 옮길 때

진입점은 `music_preprocessing/analysis/embedder.py` 로 둔다. **이번에는 구현하지
않았다** — 설정을 기록으로 고정하는 것이 이 작업의 범위다.

옮길 때 지킬 것:

- 위 1번 값을 상수로 박고, 이 문서를 가리키는 주석을 단다
- 층 평균을 특정 층으로 바꾸지 않는다. `hidden_states` 를 통째로 평균 내는 것이 규약이다
- `blk.std(0)` 을 분산이나 max 로 바꾸지 않는다
- 정규화 순서를 바꾸지 않는다 — z-score 가 먼저, L2 가 나중이다
- 3초 미만 구간을 버리는 규칙은 분할·임베딩·특징이 **같은 값**을 써야 한다

## 남은 위험

**코드에 리비전이 박혀 있지 않다.** `from_pretrained("m-a-p/MERT-v1-95M")` 는 그때그때
허브의 최신을 받는다. 가중치가 바뀌면 같은 코드로도 다른 임베딩이 나오고, 그것을 알아챌
방법이 지금 없다. 옮길 때 `revision=` 을 박는다.

캐시에서 값을 복원했다. GPU 박스
`~/.cache/huggingface/hub/models--m-a-p--MERT-v1-95M` 에 스냅샷이 둘인데, 쓰인 것은
**`12af15fef9d0ac838c3f475bfbbf26d2060dd4f5`** 다.

| 스냅샷 | 내용 | 판정 |
|---|---|---|
| `12af15fe…` | `config.json` · `configuration_MERT.py` · `modeling_MERT.py` · `pytorch_model.bin` | **`refs/main` 이 가리킨다.** `trust_remote_code=True` 가 요구하는 모델링 코드가 여기 있다 |
| `13d61510…` | `model.safetensors` 하나뿐 | 코드 파일이 없어 이 경로로는 적재되지 않는다 |

둘 다 2026-09-01 14:03 에 받았고, 생성 코드(`s24_mert.py`, 09-04)와 산출물
(`moments_info.json`, 09-07)보다 앞선다.

> 이 값은 **캐시에서 역추정한 것**이지 생성 당시 기록이 아니다. 옮긴 코드로 재현
> 시험(4번 기준)을 통과하면 그때 확정된다.
