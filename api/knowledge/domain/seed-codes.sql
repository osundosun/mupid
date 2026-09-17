-- 공통 코드(codes) 기준 데이터.
--
-- 평소에는 손댈 일이 없다 — 기동 때
-- `api/src/main/java/com/osundosun/mupid/api/bootstrap/BootstrapRunner.java` 가
-- 같은 목록으로 빠진 코드를 채운다. **두 목록은 같이 고쳐야 한다.**
-- 이 파일은 앱을 띄우지 않고 DB 만 채워야 할 때 쓴다.
--
-- 테이블 자체는 `ddl-auto` 가 `none` 이라 사람이 만든다 (decision/0011).
-- 온보딩 설문은 이 표의 번호를 주고받으므로 **이 표가 비어 있으면 온보딩이
-- 503 으로 막힌다.**
--
-- 적용:
--   docker exec -i mupid-dev-mysql mysql --default-character-set=utf8mb4 \
--     -u<USER> -p<PASSWORD> mupid < seed-codes.sql
--
-- **`--default-character-set=utf8mb4` 를 빼면 안 된다.** 클라이언트가 이 파일의
-- UTF-8 바이트를 latin1 로 읽어 다시 인코딩해서 한글이 깨진 채로 들어가는데,
-- 오류가 나지 않아 조용히 성공한다. `SELECT CHAR_LENGTH(code_name)` 이 글자 수보다
-- 크면 그렇게 들어간 것이다.
--
-- 이름 목록의 출처는 화면 디자인이다 — `frontend/lib/mupid-data.ts` 의
-- `moodOptions` · `genreOptions`. 여기를 고치면 화면도 같이 봐야 한다.
--
-- 여러 번 돌려도 늘어나지 않는다. `code_name` 은 UNIQUE 가 아니라서
-- INSERT ... SELECT 로 있는지 보고 넣는다.

-- `created_at` 을 직접 넣는다. 표를 Hibernate 가 만든 환경에서는 ERD 와 달리
-- DEFAULT CURRENT_TIMESTAMP 가 붙지 않아 생략하면 1364 로 막힌다.
--
-- `display_name` 은 NOT NULL 이고 기본값이 없다. 화면에 내보내는 이름이라
-- 지금은 `code_name` 과 같은 값을 넣는다 — 둘이 갈라지기 전까지는 같다.
INSERT INTO `codes` (`code_type`, `code_name`, `display_name`, `created_at`)
SELECT wanted.t, wanted.n, wanted.n, NOW(6) FROM (
  SELECT 'MOOD' AS t, '잔잔한' AS n
  UNION ALL SELECT 'MOOD', '벅찬'
  UNION ALL SELECT 'MOOD', '몽환적인'
  UNION ALL SELECT 'MOOD', '신나는'
  UNION ALL SELECT 'MOOD', '집중되는'
  UNION ALL SELECT 'MOOD', '감성적인'
  UNION ALL SELECT 'MOOD', '강렬한'
  UNION ALL SELECT 'MOOD', '설레는'
  UNION ALL SELECT 'GENRE', 'Electronic'
  UNION ALL SELECT 'GENRE', 'Indie'
  UNION ALL SELECT 'GENRE', 'R&B'
  UNION ALL SELECT 'GENRE', 'Rock'
  UNION ALL SELECT 'GENRE', 'Pop'
  UNION ALL SELECT 'GENRE', 'Hip-hop'
  UNION ALL SELECT 'GENRE', 'Jazz'
  UNION ALL SELECT 'GENRE', 'Classical'
) AS wanted
WHERE NOT EXISTS (
  SELECT 1 FROM `codes` c
  WHERE c.`code_type` = wanted.t AND c.`code_name` = wanted.n
);
