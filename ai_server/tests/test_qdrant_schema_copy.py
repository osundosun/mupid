"""Spring 이 쓰는 컬렉션 정의가 AI 가 읽는 정의와 같은가 (S15P21A204-457).

정본은 `ai_server/app/qdrant_collection.json` 이고, Spring 은 빌드 문맥이 `api/` 뿐이라 같은 파일을
`api/src/main/resources/qdrant/qdrant_collection.json` 에 **바이트 그대로** 복사해 쓴다. 한쪽만 고치면
차원·포인트 id 네임스페이스·벡터 이름이 갈리고, 그것은 오류가 아니라 "검색은 되는데 엉뚱한 구간" 으로만
드러난다. Spring 시험은 CI 에서 돌지 않아(빌드만 한다) 여기서 지킨다.
"""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_spring_resource_is_byte_copy_of_definition():
    source = ROOT / "ai_server" / "app" / "qdrant_collection.json"
    copy = ROOT / "api" / "src" / "main" / "resources" / "qdrant" / "qdrant_collection.json"
    assert copy.read_bytes() == source.read_bytes(), (
        "두 정의가 다르다 — ai_server/app/qdrant_collection.json 을 고쳤으면 api 리소스에 그대로 복사한다")
