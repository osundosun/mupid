# 정예준 — 진행사항

**주제** 
**기간** 2026-08-18 ~

---

## 일자별 진행사항

### 2026-08-24

- **[위성 이미지 기반 농지 이상탐지](위성_이미지_이상탐지.md)** 파이프라인 구축 및 검증
  - Sentinel-2 L2A(STAC) · Sentinel-1 GRD(GEE) · WorldCover · DEM 수집 → 10일 중앙값 합성 → 100 m 가상 필지(1 ha) 생성
  - 앙상블 구성: A1 위상정렬 robust z-score(0.55) + A2 Conv1D AutoEncoder(0.45), A3 Isolation Forest는 스윕에서 해로워 제외(0.00)
  - 결과: 87,627필지 / 44지역, RED 385개(2.1%), ROC-AUC 0.973 / PR-AUC 0.924 / lift@10% 8.2배, 초당 347필지(전국 ~1.6h)
  - 함정 8건 추적 및 해소 — 데이터 누수(AUC 1.000), 조생종 위상 차이 오독, 이모작 오염, 비-논 혼입(RED의 46%), 타이밍 오차 21배 증폭, 산림 오탐, 침수 탐지가 사실상 생육 측정
  - 개선: S1 결합으로 관측 공백 25.9% → 11.1%, 히스테리시스로 경보 유지율 50% → 99%
  - 남은 한계: 격자(1 ha) > 실제 필지(0.1~0.3 ha), 구름 지역 관측 편향, 단기 침수 탐지 불가, 필지 단위 정답 부재

### 2026-08-25

- **[LLM 양자화 및 파인튜닝](LLM_양자화_파인튜닝.md)** skeleton01~04 실습 정리 (Text-to-SQL)
  - skeleton01 — Upstage `solar-pro3` Teacher로 pseudo-label 생성(Black-box KD), `response_format` json_schema로 파싱 안정화, LLM as a Judge의 과대평가 확인(스키마 없이 추측한 SQL에 score 5)
  - skeleton02 — SmolLM2-360M LoRA(r=32) SFT, labels `-100` 마스킹으로 assistant 구간만 학습. base exec acc 0.567(easy .682 / medium .250), 100 steps 155.6s, loss 2.331 → 1.159
  - skeleton03 — SmolLM2-1.7B QLoRA(nf4 + double quant). **파라미터 4.7배 / FLOPs 4.9배 모델을 VRAM +300MiB로 학습**, 시간 오버헤드는 실측 1.19배(노트북 서술 3배와 불일치)
  - skeleton04 — vLLM 서빙. PagedAttention · KV Cache 원리를 시뮬레이터로 검증, KV Cache 9.73GiB → 동시성 31.14x. Transformers 대비 **throughput 12.74x**(단, first-token·peak memory 수치는 측정 방식 탓에 비교 무의미)
  - 공통 결론: **스키마 미주입이 구조적 병목** — JOIN 실패, `table_name` 플레이스홀더 누출. 서빙 성능은 확보했으나 모델 품질은 미달
  - 트러블슈팅 기록: `BatchEncoding.squeeze`, `datasets.map(num_proc)` pickle 실패, PEFT adapter 중첩, T4 V1 Engine 미지원(→ `dtype="half"` + `enforce_eager`)
- 다음 단계: Hadoop · Hive · Spark 분산 처리 및 쿠버네티스 배포 학습 착수

---

## 학습 기술 스택

| 분류 | 스택 | 상태 |
|---|---|---|
| AI | [LLM 양자화 및 파인튜닝](LLM_양자화_파인튜닝.md) | ✅ 완료 |
| AI | [위성 이미지 기반 농지 이상탐지 모델 학습](위성_이미지_이상탐지.md) | ✅ 완료 |
| 인프라 | Hadoop · Hive · Spark를 활용한 분산 처리 | 🔄 진행 중 |
| 인프라 | 쿠버네티스 활용 분산 서버 배포 및 관리 | 🔄 진행 중 |
