-- 공통 코드(codes) 기준 데이터.
--
-- `ddl-auto` 가 `none` 이라 스키마도 데이터도 사람이 직접 넣는다
-- (decision/0011). 온보딩 설문은 이 표의 번호를 주고받으므로 **이 표가 비어
-- 있으면 온보딩이 503 으로 막힌다.**
--
-- 적용:
--   docker exec -i mupid-dev-mysql mysql -u<USER> -p<PASSWORD> mupid < seed-codes.sql
--
-- 이름 목록의 출처는 화면 디자인이다 — `frontend/lib/mupid-data.ts` 의
-- `moodOptions` · `genreOptions`. 여기를 고치면 화면도 같이 봐야 한다.
--
-- 여러 번 돌려도 늘어나지 않는다. `code_name` 은 UNIQUE 가 아니라서
-- INSERT ... SELECT 로 있는지 보고 넣는다.

-- `created_at` 을 직접 넣는다. 표를 Hibernate 가 만든 환경에서는 ERD 와 달리
-- DEFAULT CURRENT_TIMESTAMP 가 붙지 않아 생략하면 1364 로 막힌다.
INSERT INTO `codes` (`code_type`, `code_name`, `created_at`)
SELECT wanted.t, wanted.n, NOW(6) FROM (
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
