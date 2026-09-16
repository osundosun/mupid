"""구간 분석 — 신곡을 기존 특징 공간으로 옮긴다.

전처리 본체(Opus 청킹·등록)와 달리 numpy·scipy 가 필요하다. 그래서 선택 의존성으로
분리한다 — 스트리밍 청킹만 쓰는 쪽이 수백 MB 를 받을 이유가 없다.

    uv sync --extra analysis        (또는 pip install -e '.[analysis]')
"""
