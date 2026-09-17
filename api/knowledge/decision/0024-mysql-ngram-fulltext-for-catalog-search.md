# 0024. 카탈로그 통합 검색은 MySQL ngram FULLTEXT 로 시작한다

**상태** 채택

## 맥락

상단 검색창은 검색어 하나로 곡 제목·아티스트명·앨범명을 모두 찾아야 한다. 기존
`MusicRepository.search` 는 세 컬럼에 `LIKE '%검색어%'` 를 OR 로 걸었다. 이 방식은
기능상 맞지만 선행 와일드카드 때문에 B-Tree 인덱스를 사용할 수 없고, 카탈로그가
커질수록 곡과 조인 대상 테이블을 넓게 읽는다.

한국어와 영문이 섞인 음악 메타데이터라 일반 MySQL FULLTEXT 기본 파서는 한국어
검색 품질이 충분하지 않다. 반면 지금 규모에서 Elasticsearch/OpenSearch를 새로
운영하면 색인 동기화·장애 복구·배포 비용이 검색 기능 자체보다 커진다.

## 결정

- `music.title`, `artists.name`, `albums.title` 각각에 MySQL 8.4 ngram FULLTEXT
  인덱스를 둔다.
- 세 인덱스를 한 OR 문으로 묶지 않는다. 테이블별 인덱스 검색 결과를 `UNION ALL`
  하고 `music_id` 로 합쳐서 각 인덱스를 독립적으로 사용할 수 있게 한다.
- 관련도는 곡 제목 3, 아티스트명 2, 앨범명 1의 가중치를 준다. 같은 곡이 여러
  필드에서 일치하면 가장 높은 점수를 기준으로 정렬한다.
- 여러 단어는 Boolean mode의 필수 prefix 토큰(`+blue* +hour*`)으로 바꾼다.
  검색 연산자는 입력에서 제거하고 파라미터 바인딩을 유지한다.
- ngram 기본 최소 단위보다 짧은 한 글자 검색은 `LIKE '검색어%'` 로만 보완한다.
  이를 위해 세 텍스트 컬럼에 일반 B-Tree 인덱스도 둔다. 한 글자 중간 일치를 위해
  전체 스캔으로 되돌아가지는 않는다.
- 검색어가 없을 때의 전체 목록 조회는 기존 최신 등록순 쿼리를 유지한다.
- `ddl-auto` 가 `none` 이므로 신규 DB 기준은 `domain/erd.sql`, 기존 DB 적용 DDL은
  `domain/apply-search-indexes.sql` 에 함께 둔다.

## 결과

대부분의 검색은 카탈로그 전체 `LIKE` 스캔 대신 전문 인덱스를 사용하고, 한 번의
API 호출로 세 검색 대상을 통합해 관련도순 곡 목록을 돌려준다. 별도 검색 서비스를
운영하거나 원본 DB와 색인을 동기화할 필요가 없다.

받아들인 비용도 있다.

- FULLTEXT 인덱스 세 개와 prefix 보완 인덱스 세 개만큼 저장 공간과 메타데이터 쓰기
  비용이 늘어난다.
- 한 글자 검색은 접두 일치만 지원하므로 두 글자 이상 검색보다 recall이 낮다.
- MySQL 관련도와 고정 가중치는 오탈자 교정·동의어·개인화 랭킹을 제공하지 않는다.
- 기존 DB에는 앱 배포 전에 수동 DDL을 적용해야 한다. 적용하지 않으면 MATCH 쿼리가
  `Can't find FULLTEXT index matching the column list` 로 실패한다.

카탈로그 규모나 검색 트래픽 때문에 MySQL 쓰기/조회 지연이 문제가 되거나, 오탈자
교정·동의어·다국어 형태소·개인화 랭킹이 제품 요구가 되면 전용 검색엔진 도입을
새 ADR로 검토한다. 그 전에는 운영 구성요소를 추가하지 않는다.

## 근거

- `src/main/java/com/osundosun/mupid/api/music/repository/MusicRepository.java`
- `src/main/java/com/osundosun/mupid/api/music/service/MusicServiceImpl.java`
- `knowledge/domain/erd.sql`
- `knowledge/domain/apply-search-indexes.sql`
- `frontend/components/mupid/explore.tsx`
