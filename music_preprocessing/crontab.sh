# music_preprocessing 의 실제 크론 항목. 배포가 이 파일을 서버 crontab 에 넣는다.
#
# crontab.example.sh 는 설명용이고 이 파일이 실물이다. 스케줄을 바꾸려면 여기를
# 고쳐 MR 을 올린다 — 서버에서 `crontab -e` 로 고치면 다음 배포가 되돌린다.
#
# 배포(infra/ci/deploy-jupyter.py)는 아래 BEGIN/END 마커 사이만 갈아 끼운다.
# 마커 밖의 다른 크론 항목은 건드리지 않는다. 공용 서버라 남의 항목을 지우면
# 안 되고, "다시 연결" 이 "전부 덮어쓰기" 가 되어서도 안 된다.
#
# {{UV}} 와 {{DIR}} 은 배포가 서버에서 찾아낸 실제 경로로 바꾼다. uv 가 어디
# 깔려 있는지는 서버마다 다르고, 크론은 PATH 가 거의 비어 있어 절대경로가
# 아니면 조용히 실패한다.
#
# PATH 에 {{DIR}}/bin 이 앞서는 이유: 서버에 ffmpeg 이 없어서 conda 환경의
# ffmpeg 을 그리로 심볼릭 링크해 쓴다. 이게 빠지면 청킹이 전부 실패한다.

# BEGIN music_preprocessing
PATH={{DIR}}/bin:/usr/local/bin:/usr/bin:/bin
MUSIC_PREPROCESSING_DIR={{DIR}}

# 카탈로그가 먼저다 — 곡이 있어야 batch 가 스트림을 붙인다.
0 2 * * * cd {{DIR}} && {{UV}} run music-preprocessing catalog -c config.json --yes --no-progress
# 커버는 바뀐 그림만 올린다. 변경이 없으면 해시만 비교하고 끝난다.
15 2 * * * cd {{DIR}} && {{UV}} run music-preprocessing covers -c config.json --yes --no-progress
# 가사는 곡 번호가 있어야 붙일 수 있다 — catalog 다음.
30 2 * * * cd {{DIR}} && {{UV}} run music-preprocessing lyrics -c config.json --yes --no-progress
# 청킹. 해시 기반이라 이미 처리한 곡은 건너뛴다.
0 3 * * * cd {{DIR}} && {{UV}} run music-preprocessing batch -c config.json
# 30일 지난 로그 삭제.
30 3 * * * find {{DIR}}/logs -name '*.log' -mtime +30 -delete
# END music_preprocessing
