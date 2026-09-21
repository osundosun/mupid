-- `onboarding_survey` 의 무드 124~132 참조를 32~48 로 되돌린다 (2026-09-21).
--
-- 무엇이 문제였나: 코드표에 같은 무드가 중복으로 들어가 있었고(124~132 는 32~48 의
--   17종 중 taxonomy 9종이 `code_name`·`display_name` 둘 다 한국어로 다시 들어간 것),
--   온보딩 화면이 그 중복 칩을 내놓아 **사용자가 124~132 를 고른 답이 쌓였다.**
--   그래서 124~132 를 지우려 하면 `fk_onboarding_survey_mood_code_id` 가 막는다.
--
-- 무엇으로 맞추나: `display_name` 이 같은 32~48 의 번호로 옮긴다. 사용자가 화면에서
--   본 글자가 곧 그 무드이므로, 같은 글자의 동결 번호가 같은 답이다.
--   동결 규약은 `../decision/0025-mood-codes-32-48-are-frozen.md`.
--
-- 범위: **이 파일은 `onboarding_survey` 만 건드린다.**
--   `codes` 의 124~132 DELETE 는 하지 않는다 — 사람이 따로 한다.
--   `music_moods`·`music_section_analysis_mood`·`daily_recommendations` 는 0단계에서
--   참조가 없음을 확인하고 손대지 않는다. 있으면 멈추고 사람이 판단한다.
--
-- 실행:
--   "/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" \
--     -h 127.0.0.1 -P 23306 -u mupid_app -p --default-character-set=utf8mb4 mupid \
--     < migrate-onboarding-survey-mood-124-132-2026-09-21.sql
--
--   **`--default-character-set=utf8mb4` 를 빼면 안 된다.** 이 파일은 한글로 대조하지
--   않고 `display_name` 끼리 조인하므로 값이 깨져 들어갈 일은 없지만, 확인 SELECT 의
--   출력이 깨져 결과를 잘못 읽게 된다.

-- ============================================================ 0. 전제 확인
-- 아래 세 SELECT 의 결과가 기대와 다르면 **여기서 멈추고** 상태를 다시 본다.

-- 0-1. 옮길 대상이 몇 건인가. (0 이면 이미 끝났거나 대상이 없다)
SELECT COUNT(*) AS rows_to_move
FROM onboarding_survey
WHERE mood_code_id BETWEEN 124 AND 132;

-- 0-2. 124~132 의 각 행이 32~48 에서 **정확히 하나씩** 짝을 찾는가.
--      기대: 9행이 나오고 `keepers` 가 전부 1 이다.
--      0 인 줄이 있으면 그 무드는 옮길 곳이 없다 — 멈춘다.
--      2 이상인 줄이 있으면 32~48 안에 표시명이 겹친다 — 멈춘다 (ADR 0025 위반 상태).
SELECT old.code_id,
       old.display_name,
       (SELECT COUNT(*) FROM codes k
         WHERE k.code_type = 'MOOD'
           AND k.code_id BETWEEN 32 AND 48
           AND k.display_name = old.display_name) AS keepers
FROM codes old
WHERE old.code_type = 'MOOD' AND old.code_id BETWEEN 124 AND 132
ORDER BY old.code_id;

-- 0-3. onboarding_survey 말고 124~132 를 참조하는 곳이 있는가.
--      기대: 세 줄 모두 0. 0 이 아니면 이 파일로는 부족하다 — 멈춘다.
SELECT 'music_moods' AS referencing_table, COUNT(*) AS refs
  FROM music_moods WHERE mood_code_id BETWEEN 124 AND 132
UNION ALL
SELECT 'music_section_analysis_mood', COUNT(*)
  FROM music_section_analysis_mood WHERE mood_code_id BETWEEN 124 AND 132
UNION ALL
SELECT 'daily_recommendations', COUNT(*)
  FROM daily_recommendations WHERE mood_code_id BETWEEN 124 AND 132;

-- ============================================================ 1. 이관
START TRANSACTION;

-- 1-1. 옮기면 같은 답이 두 줄이 되는 것부터 지운다.
--
--      `onboarding_survey` 는 (분위기 × 장르) 조합을 한 줄씩 깔아 둔다. 같은 사용자가
--      화면에서 중복된 두 칩(`고요한` 47 과 `고요한` 132)을 **둘 다** 골랐다면, 옮긴
--      뒤 (user, 47, genre) 가 두 줄이 된다. 표에 UNIQUE 가 없어 오류는 안 나지만
--      같은 답이 두 번 세어진다.
--
--      지워도 잃는 것이 없다 — 남기는 쪽이 이미 같은 답이다.
DELETE a FROM onboarding_survey a
JOIN codes old
  ON old.code_id = a.mood_code_id
 AND old.code_type = 'MOOD'
JOIN codes keeper
  ON keeper.code_type = 'MOOD'
 AND keeper.code_id BETWEEN 32 AND 48
 AND keeper.display_name = old.display_name
JOIN onboarding_survey b
  ON b.user_id = a.user_id
 AND b.genre_code_id = a.genre_code_id
 AND b.mood_code_id = keeper.code_id
WHERE a.mood_code_id BETWEEN 124 AND 132;

-- 1-2. 남은 것을 같은 표시명의 동결 번호로 옮긴다.
UPDATE onboarding_survey a
JOIN codes old
  ON old.code_id = a.mood_code_id
 AND old.code_type = 'MOOD'
JOIN codes keeper
  ON keeper.code_type = 'MOOD'
 AND keeper.code_id BETWEEN 32 AND 48
 AND keeper.display_name = old.display_name
SET a.mood_code_id = keeper.code_id
WHERE a.mood_code_id BETWEEN 124 AND 132;

COMMIT;

-- ============================================================ 2. 확인
-- 2-1. 기대: 0행. 남아 있으면 그 무드는 32~48 에서 짝을 못 찾은 것이다 (0-2 를 다시 본다).
SELECT mood_code_id, COUNT(*) AS remaining
FROM onboarding_survey
WHERE mood_code_id BETWEEN 124 AND 132
GROUP BY mood_code_id;

-- 2-2. 기대: 0행. 모든 답이 동결 구간 안에 있다.
SELECT mood_code_id, COUNT(*) AS outside_frozen_range
FROM onboarding_survey
WHERE mood_code_id NOT BETWEEN 32 AND 48
GROUP BY mood_code_id
ORDER BY mood_code_id;

-- 2-3. 기대: 0행. 같은 (사용자, 분위기, 장르) 가 두 줄이 되지 않았다.
SELECT user_id, mood_code_id, genre_code_id, COUNT(*) AS dup
FROM onboarding_survey
GROUP BY 1, 2, 3
HAVING COUNT(*) > 1;

-- 2-4. 이제 `codes` 의 124~132 를 지울 수 있다 — **사람이 따로 한다.**
--      FK 가 더는 막지 않아야 한다. 막힌다면 위 0-3 을 다시 본다.
--      DELETE FROM codes WHERE code_type = 'MOOD' AND code_id BETWEEN 124 AND 132;
