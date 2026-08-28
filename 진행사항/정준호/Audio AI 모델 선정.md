# 260827 — Audio AI 모델 선정

## 오늘 한 일

- Spotify·YouTube Music Samples·SoundCloud·Cyanite 등 유사 서비스 기능 비교
- FMA 음원을 Window 단위로 처리하는 오디오 분석 파이프라인 설계
- 음악 구간 Embedding 모델로 MERT-v1-95M 검토
- BPM·Energy·Rhythm·Spectral Feature 추출을 위한 Essentia 활용 검토
- 유사한 Moment 변화 패턴 탐색을 위한 HDBSCAN·K-Means 비교
- 사용자 선호 Moment를 누적해 공통 패턴을 찾는 추천 구조 구체화

## 유사 서비스 조사

### Spotify

- 청취 이력·플레이리스트·아티스트·곡 단위의 개인화 추천 제공
- Discover Weekly·Radio 등 곡 전체 중심의 추천 경험이 강점
- 사용자가 좋아한 특정 timestamp의 변화 패턴을 직접 취향 정보로 다루지는 않음

### YouTube Music Samples

- 짧은 구간을 빠르게 넘기며 새로운 음악을 발견할 수 있음
- 하이라이트 중심 탐색에는 유리하지만 특정 구간의 음악적 구조를 분석해 추천하는 방식과는 차이가 있음

### SoundCloud

- 파형 위에 댓글을 남길 수 있어 특정 timestamp를 중심으로 반응을 기록 가능
- 구간별 사용자 반응은 확인할 수 있지만 해당 순간의 특징을 다른 곡의 유사한 변화와 연결하는 기능은 제한적

### Cyanite

- 음악의 분위기·장르·태그·유사도 분석 등 AI 기반 음악 검색 기능 제공
- B2B 음악 분류와 카탈로그 검색에 강점
- 사용자 개인이 좋아한 순간의 전후 변화 패턴을 누적하는 추천 구조와는 목적이 다름

## 차별화 방향

기존 서비스와의 차이는 곡 전체의 유사도가 아니라 사용자가 반응한 순간의 전후 변화를 취향 데이터로 사용한다는 점이다.

```plaintext
기존 추천
곡 A를 좋아함 → 곡 A와 비슷한 곡 추천

제안 서비스
곡 A의 특정 Moment를 좋아함
→ 해당 순간의 변화 패턴 분석
→ 장르와 관계없이 유사한 Moment 추천
```

## 오디오 분석 파이프라인

FMA 음원을 일정 길이의 Window로 분리하고, 각 구간에서 저수준 Audio Feature와 Embedding을 추출한 뒤 인접 구간의 변화량을 계산하는 구조를 설계했다.

```plaintext
Audio File
→ Decode / Resample
→ Window 분할
→ Essentia Feature 추출
→ MERT Embedding 생성
→ 인접 Window 차이 계산
→ Transition Vector 생성
→ 군집화 및 유사도 검색
```

### Window 처리

- 음원을 일정한 길이로 나누되 Moment의 변화가 잘 드러나는 크기를 실험으로 결정
- Window가 너무 짧으면 특징이 불안정하고, 너무 길면 짧은 전환을 놓칠 수 있음
- 초기에는 고정 길이 Window와 일정 간격의 Overlap을 적용하는 방안 검토

### Audio Feature

- BPM과 Tempo
- RMS Energy
- Onset Rate와 Rhythm 관련 특징
- Spectral Centroid·Bandwidth·Rolloff
- MFCC 등 음색 특징
- 저·중·고역대 에너지 분포

## 모델 및 도구 선정

### MERT-v1-95M

- 음악에 특화된 사전학습 모델로 구간별 Audio Embedding 추출에 활용
- 음색·리듬·악기 구성 등 손으로 정의하기 어려운 고차원 특징을 표현
- 더 큰 모델보다 초기 실험과 처리 비용의 균형을 고려해 95M 모델을 우선 검토
- Window별 Embedding과 인접 Window 간 차이를 함께 사용해 Moment 특징 표현

### Essentia

- BPM·Energy·Rhythm·Spectral Feature 등 해석 가능한 저수준 특징 추출에 활용
- Embedding만 사용할 때 설명하기 어려운 추천 근거를 보완
- 사용자가 좋아한 순간을 “에너지가 상승하고 리듬 밀도가 증가한 구간”처럼 설명하는 데 활용 가능

### HDBSCAN / K-Means

- 정답 라벨이 없는 Moment 패턴을 찾기 위해 비지도 군집화 우선 검토
- K-Means
  - 구조가 단순하고 대규모 데이터 처리와 기준 실험에 유리
  - 군집 수를 미리 정해야 함
- HDBSCAN
  - 군집 수를 미리 정하지 않아도 되고 잡음 데이터를 분리할 수 있음
  - 불규칙한 형태의 Moment 군집 탐색에 유리할 가능성
- 우선 K-Means로 기준 결과를 만든 뒤 군집 형태가 복잡하거나 잡음 처리가 필요할 때 HDBSCAN과 비교하는 방향

## 사용자 취향 누적 구조

사용자가 여러 곡에서 남긴 선호 Moment를 누적하면 반복적으로 나타나는 공통 변화 패턴을 개인 취향으로 표현할 수 있다.

```plaintext
선호 Moment 1 ┐
선호 Moment 2 ├→ 공통 변화 패턴 → 사용자 취향 벡터
선호 Moment 3 ┘
```

- 단일 Moment만으로 취향을 확정하지 않고 선호 데이터가 쌓일수록 가중치를 조정
- 자주 선택한 변화 유형을 핵심 취향으로 반영
- 서로 다른 선호 패턴이 존재하면 하나의 평균으로 합치지 않고 복수 취향 군집으로 관리하는 방향 검토

## 다음에 할 것

- 소규모 음원으로 Window 분할과 특징 추출 파이프라인 구현
- Window 길이·Overlap·Moment 범위별 결과 비교
- MERT Embedding과 Essentia Feature의 조합 방식 실험
- K-Means 기준 모델을 만든 뒤 HDBSCAN 결과와 비교
- 유사 Moment 검색 결과를 직접 청취해 정성 평가
