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
-- **셋(`BootstrapRunner.CODE_SEEDS` · 이 파일 · `mupid-data.ts`)을 함께 고친다.**
--
-- 무드는 **17종**이다 (2026-09-17). 앞 8종이 화면 디자인의 대분류, 뒤 9종이
-- taxonomy(`genre_mood_taxonomy.json`) 세분류다. taxonomy 9종은 색인 축에 이름이
-- 하나도 없어 뜻으로 이어야 했고(`ai_server/app/onboarding.py` 의 `MOOD_MAP`),
-- 그때 **`code_name` 은 화면용 한국어, `display_name` 이 색인 원문**이 된다 —
-- 장르 45종과 방향이 반대라 여기서 헷갈리기 쉽다.
--
-- `display_name` 은 NOT NULL 이라 값을 빼면 1364 로 막힌다. 위 INSERT 는
-- 표시명을 항상 함께 넣는다 (기존 16종은 `code_name` 과 같은 값).
--
-- **무드 9종은 위 8종 뒤에 붙인다.** 화면 차례가 곧 `code_id` 차례라
-- (`findAllByCodeTypeOrderByCodeIdAsc`) 사이에 끼우면 기존 사용자의
-- `onboarding_survey` 답이 다른 분위기로 읽힌다.
--
-- 여러 번 돌려도 늘어나지 않는다. `code_name` 은 UNIQUE 가 아니라서
-- INSERT ... SELECT 로 있는지 보고 넣는다.

-- `created_at` 을 직접 넣는다. 표를 Hibernate 가 만든 환경에서는 ERD 와 달리
-- DEFAULT CURRENT_TIMESTAMP 가 붙지 않아 생략하면 1364 로 막힌다.
INSERT INTO `codes` (`code_type`, `code_name`, `display_name`, `created_at`)
SELECT wanted.t, wanted.n, wanted.d, NOW(6) FROM (
  SELECT 'MOOD' AS t, '잔잔한' AS n, '잔잔한' AS d
  UNION ALL SELECT 'MOOD', '벅찬', '벅찬'
  UNION ALL SELECT 'MOOD', '몽환적인', '몽환적인'
  UNION ALL SELECT 'MOOD', '신나는', '신나는'
  UNION ALL SELECT 'MOOD', '집중되는', '집중되는'
  UNION ALL SELECT 'MOOD', '감성적인', '감성적인'
  UNION ALL SELECT 'MOOD', '강렬한', '강렬한'
  UNION ALL SELECT 'MOOD', '설레는', '설레는'
  -- taxonomy 무드 9종 (2026-09-17 추가). `code_name` 은 화면용 한국어,
  -- `display_name` 이 색인 원문이다 — **장르 45종과 방향이 반대다.**
  -- 반드시 위 8종 **뒤에** 붙인다. 화면 차례가 곧 `code_id` 차례라
  -- 사이에 끼우면 기존 `onboarding_survey` 답이 다른 분위기로 읽힌다.
  UNION ALL SELECT 'MOOD', '애상적인', 'melancholic'
  UNION ALL SELECT 'MOOD', '따뜻한', 'warm'
  UNION ALL SELECT 'MOOD', '몽글한', 'dreamy'
  UNION ALL SELECT 'MOOD', '화사한', 'bright'
  UNION ALL SELECT 'MOOD', '희망찬', 'hopeful'
  UNION ALL SELECT 'MOOD', '웅장한', 'powerful'
  UNION ALL SELECT 'MOOD', '어두운', 'dark'
  UNION ALL SELECT 'MOOD', '고요한', 'calm'
  UNION ALL SELECT 'MOOD', '짜릿한', 'exciting'
  UNION ALL SELECT 'GENRE', 'Electronic', 'Electronic'
  UNION ALL SELECT 'GENRE', 'Indie', 'Indie'
  UNION ALL SELECT 'GENRE', 'R&B', 'R&B'
  UNION ALL SELECT 'GENRE', 'Rock', 'Rock'
  UNION ALL SELECT 'GENRE', 'Pop', 'Pop'
  UNION ALL SELECT 'GENRE', 'Hip-hop', 'Hip-hop'
  UNION ALL SELECT 'GENRE', 'Jazz', 'Jazz'
  UNION ALL SELECT 'GENRE', 'Classical', 'Classical'
) AS wanted
WHERE NOT EXISTS (
  SELECT 1 FROM `codes` c
  WHERE c.`code_type` = wanted.t AND c.`code_name` = wanted.n
);
