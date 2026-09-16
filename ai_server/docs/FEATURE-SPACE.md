# 285차 특징 공간 — 신곡을 여기에 얹는 방법

추천 랭킹이 보는 입력은 구간 하나당 **285차 벡터**다. 이 문서는 그 구성과, 신곡을
**기존 공간에 그대로 넣기 위한 규칙**을 적는다.

핵심은 하나다. **다시 적합하지 않는다.** 기저와 통계는 이미 정해져 있고, 신곡은 그것을
읽어 쓰기만 한다.

## 1. 구성

```
X = [ MERT PCA 256 (동결 기저) | side 29 ]   = 285
```

재현식은 `ai_server/app/features.py` 의 `fit()` 이 정본이다.

```python
P = (moments_emb[emb_row] - pca_mean) @ pca_comp
P = P / ||P||                     # 행별 L2
X = concat([P, side_features(...)])
```

**행별 L2 를 빼면 오차가 0.119 까지 벌어진다.** 눈에 띄는 오류는 나지 않고 순위만
틀어진다. PCA 기저(`pca_mean (1536,)` · `pca_comp (1536,256)`)는 `features_v1.npz`
안에 들어 있고, 설명분산은 0.847 이다.

### side 29열 (순서가 곧 계약이다)

| 자리 | 열 | 계산 |
|---|---|---|
| 0–5 | `rel_start` `rel_end` `rel_center` `log_dur` `rms_pct` `rms_z` | `rel_center=(start+end)/2`, `log_dur=log1p(dur_sec)` |
| 6–15 | `pos0`…`pos9` | `rel_center` 를 10구간으로 자른 원핫 |
| 16–23 | `lab_intro` `lab_verse` `lab_chorus` `lab_bridge` `lab_inst` `lab_solo` `lab_outro` `lab_other` | 목록에 없는 라벨은 **`other` 로 떨어진다** |
| 24–28 | `trk_bpm` `trk_centroid` `trk_rolloff` `trk_rms_mean` `trk_flatness` | 트랙 레벨 값의 z-score |

**열 순서가 바뀌면 오차 없이 엉뚱한 열이 모델에 들어간다.** 그래서 이름과 순서를
`feature_stats_v1.npz` 에 함께 굳혀 두고, 변환기는 코드에 목록을 두지 않고 그 파일을
읽어 해석한다.

## 2. 동결 통계 — 왜 필요한가

`side_features()` 의 `_z()` 는 **넘긴 표 전체**로 평균·표준편차를 잡는다. 그래서 신곡을
그냥 얹으면 두 갈래 모두 틀린다.

- **신곡을 섞어 다시 만든다** → 기존 10,007구간의 값이 전부 바뀐다. 모델은 지금 분포로
  학습됐으므로 입력이 통째로 어긋난다.
- **신곡만 따로 계산한다** → 그 곡 안에서의 z 가 되어 아예 다른 값이 된다.

어느 쪽이든 **오류는 나지 않고 추천만 이상해진다.**

그래서 `moments.parquet` **전체 65,540행**(fma 포함 — 추천 풀 10,007이 아니다) 기준으로
6쌍을 뽑아 `feature_stats_v1.npz` 에 굳혔다.

| 키 | mean | std |
|---|---:|---:|
| `rms` | 0.132886 | 0.093956 |
| `trk_bpm` | 109.644135 | 43.291216 |
| `trk_centroid` | 1832.861987 | 760.361642 |
| `trk_rolloff` | 3746.210484 | 1701.837836 |
| `trk_rms_mean` | 0.156602 | 0.083163 |
| `trk_flatness` | 0.013706 | 0.024537 |

`nanmean`/`nanstd` 로 계산하고 **그 뒤에** 0 으로 채운다. `std <= 1e-9` 면 1.0 을 쓴다.
트랙 레벨 값도 곡 단위가 아니라 **구간 단위로 펼친 뒤** 통계를 낸다 — 구간이 많은 곡이
그만큼 더 반영된다.

`rms_pct` 는 **곡 안에서의 순위**라 신곡에도 그대로 계산된다. 동결 대상이 아니다.

### 다시 적합하면 무엇이 깨지나

`stage1_weights.npz` 에 `in_dim 285` 가 박혀 있다. 기저가 바뀌면 차원은 같아도 의미가
달라지고, 차원까지 바뀌면 `Catalog` 이 `model_error` 를 세우고 **규칙 점수로 조용히
내려간다.** 서비스는 정상으로 뜨고 추천 품질만 떨어진다.

그래서 변환기는 판이 어긋나면 **경고가 아니라 오류로 멈춘다.**

- `feature_stats_v1.npz` 가 없으면 → 멈춘다 (조용히 계산하지 않는다)
- `features_info.json` 의 `feature_version` 이 다르면 → 멈춘다
- `features_v1.npz` 의 sha256 이 통계를 뽑을 때와 다르면 → 멈춘다
- 가중치의 `feature_version` 이 다르면 → 멈춘다

**색인을 다시 만들면 `freeze_feature_stats` 도 다시 돌린다.**

## 3. 신곡이 추가로 필요로 하는 것

### 트랙 레벨 5값

`content.parquet` 이 지금 들고 있는 것과 **같은 정의**여야 한다 — `bpm` · `centroid` ·
`rolloff` · `rms_mean` · `flatness`. 정본은 `~/rec_mupid/src/m02_content.py` 이고,
`analysis/content.py` 에 그대로 옮겨 적었다. **배치가 직접 잰다.**

```
중앙 90초 창 → |stft(n_fft=2048, hop=512)|
bpm       스펙트럼 플럭스 자기상관 (50~190). librosa.beat 은 segfault 라 쓰지 않는다
centroid  spectral_centroid(S).mean()      rolloff   spectral_rolloff(S).mean()
flatness  spectral_flatness(S).mean()      rms_mean  **곡 전체** RMS (중앙 창이 아니다)
```

> **`trk_bpm` 은 측정값이다.** 앞서는 `bpm_requested` 를 우선 쓰려 했는데 실측으로
> 그것이 틀렸다는 것이 드러났다(2026-09-16).
>
> `trk_bpm` 은 동결 통계 **평균 109.644 · 표준편차 43.291** 로 z-score 되고, 그 통계는
> 측정 BPM 의 분포다(`content.bpm` 전체 108.963 / 43.172 와 사실상 같다). 요청값의
> 분포는 **평균 111.0 · 표준편차 25.6** 으로 폭이 절반이라, 그 값을 넣으면 신곡만
> 중앙으로 몰린다.
>
> flow 763곡 중 **246곡**은 측정 BPM 이 생성 요청 범위 밖이고 평균 절대차가 15.4 BPM
> 이다. `T000002` 는 요청 114~122 인데 측정 57.4 — 전형적인 옥타브 오류다. 그러나
> **그 오류까지 포함한 분포가 색인**이고, 신곡을 같은 공간에 놓으려면 같은 방법으로
> 재야 한다.
>
> `bpm_requested` 는 **다른 자리**에서 쓴다 — `flow_meta` 의 `bpm_lo`·`bpm_hi` 와
> 추천기의 BPM 필터다. 거기서는 요청값이 맞다. 두 자리를 섞지 않는다.

### 구간 메타

`rel_start` · `rel_end` · `dur_sec` · `rms` · `section_label`. 전부 구간 분할 결과와
음원에서 나온다. `section_label` 은 All-In-One 이 직접 준다(`label_source`
`allin1_harmonix`).

## 4. 분석이 만들지 않는 것

`flow_meta.parquet` 의 **genre · mood · instrument · vocal · language** 는 분석이
만들지 않는다. 생성 메타와 태그에서 온다.

없으면 신곡은 **추천 풀에는 들어가지만 조건 검색에서만 조용히 빠진다.** `filter_query_pool`
의 게이트가 해당 값을 못 찾아 그 곡을 거르기 때문이다. 오류는 나지 않고, "왜 이 곡은
장르 검색에 안 잡히지" 로만 드러난다. 신곡을 넣을 때 이 표도 함께 채워야 한다.

## 5. 쓰는 법

변환기는 **전처리 배치 쪽**에 있다. 서빙은 이 변환을 쓰지 않는다 — 이미 계산된 색인을
읽을 뿐이다.

```python
from music_preprocessing.analysis.feature_space import FeatureSpace, Section, TrackStats

space = FeatureSpace(data_dir)                  # 판 검사는 여기서 한다
X = space.transform(sections, emb_1536, track)  # (n, 285)
S = space.enc_sec(X)                            # (n, 128) = stage1_section.npy 한 행
```

`enc_sec` 은 `enc0/ln/enc1` 로 `L2(enc(x))` 를 계산한다. 사용자 인코더와 달리 주의·전역
평균 빼기가 붙지 않고, **torch 가 필요 없다**(`ai_server/app/stage1_np.py` 와 같은 수식).

## 6. 검증

`ai_server/tests/test_feature_transform.py` 가 기존 색인과 왕복 대조한다. 산출물이 있을
때만 돈다.

| 대상 | 기준 | 실측 |
|---|---|---|
| PCA 블록 (무작위 200구간) | < 1e-6 | **2.384e-07** |
| side 29열 (**전량 65,540행**) | 완전 일치 | **0.0** |
| `enc_sec` (무작위 200구간) | < 1e-6 | **1.788e-07** |

열 이름·순서, 없는 라벨의 `other` 낙하, 동결 통계·sha256·판 이름 불일치 시 멈추는지도
함께 본다.
