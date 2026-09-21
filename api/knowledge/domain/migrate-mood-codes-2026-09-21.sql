-- 운영 DB `codes(MOOD)` 정리 (2026-09-21).
--
-- 무엇이 문제였나: 같은 무드가 두 번 들어가 있었다.
--   * 32~39  대분류 8종. `code_name` 이 한국어(`잔잔한`), `display_name` 이 빈 값.
--   * 40~48  taxonomy 9종. `code_name` 이 영문(`melancholic`), `display_name` 이 한국어
--            (그중 45 `강렬한`·48 `신나는` 은 38·35 와 화면에서 같은 글자였다).
--   * 98~106 taxonomy 9종이 **다시** 들어간 것. `code_name` 이 한국어(`애상적인`),
--            `display_name` 이 영문 — 옛 BootstrapRunner 가 한국어 이름이 없다고 보고
--            2026-09-18 기동 때 넣었다.
--   `music_moods` 는 40~48 을, `music_section_analysis_mood`·`daily_recommendations` 는
--   32~39 를, `onboarding_survey` 는 32~48·100 을 섞어 쓰고 있었다.
--
-- 무엇으로 맞추나: `code_name` 은 영문 내부 코드, `display_name` 은 화면용 한국어.
--   장르 45종과 같은 방향이다. 17종의 번호는 32~48 을 그대로 쓰고 98~106 을 지운다.
--
-- 참조 이관 (요구: 같은 무드가 유지되게):
--   * onboarding_survey 100(몽글한) → 42(dreamy). user 8 은 42 도 같은 장르로 갖고 있어
--     100 쪽 두 줄(answer_id 52·53)은 지우면 곧 같은 답이다.
--   * onboarding_survey 45 → 38, 48 → 35. 사용자가 본 글자가 `강렬한`·`신나는` 이었고
--     정리 뒤 45·48 은 `웅장한`·`짜릿한` 으로 바뀌므로, 본 글자를 지키려면 38·35 다.
--   * music_moods(40~48)·music_section_analysis_mood(32~39)·daily_recommendations(32~35)
--     는 유지 번호만 쓰고 있어 손대지 않는다.
--
-- 실행: MySQL Workbench 에서 `mupid` 스키마에 통째로 실행한다 (한 트랜잭션).
--   백업은 따로 만들지 않는다 (이미 확보됨).
--   **클라이언트 문자셋이 utf8mb4 여야 한다.** 한글이 깨진 채 들어가도 오류가 나지 않는다.

START TRANSACTION;

-- 0. 전제 확인. 각 SELECT 가 0 이 아니면 아래를 실행하지 말고 상태를 다시 본다.
SELECT COUNT(*) AS must_be_0_other_refs_to_98_106
FROM (
  SELECT mood_code_id FROM music_moods WHERE mood_code_id BETWEEN 98 AND 106
  UNION ALL SELECT mood_code_id FROM music_section_analysis_mood WHERE mood_code_id BETWEEN 98 AND 106
  UNION ALL SELECT mood_code_id FROM daily_recommendations WHERE mood_code_id BETWEEN 98 AND 106
) AS refs;
SELECT COUNT(*) AS must_be_0_new_names_taken
FROM codes WHERE code_type = 'MOOD'
  AND code_name IN ('serene','uplifting','ethereal','energetic','focused','emotional','intense','romantic');

-- 1. onboarding_survey 이관.
--    100 → 42 는 이미 같은 (user, genre) 의 42 답이 있으면 지우고, 없으면 번호만 바꾼다.
DELETE a FROM onboarding_survey a
JOIN onboarding_survey b
  ON a.user_id = b.user_id AND a.genre_code_id = b.genre_code_id
WHERE a.mood_code_id = 100 AND b.mood_code_id = 42;
UPDATE onboarding_survey SET mood_code_id = 42 WHERE mood_code_id = 100;

--    45(powerful, 화면 '강렬한') → 38(강렬한), 48(exciting, 화면 '신나는') → 35(신나는).
DELETE a FROM onboarding_survey a
JOIN onboarding_survey b
  ON a.user_id = b.user_id AND a.genre_code_id = b.genre_code_id
WHERE (a.mood_code_id = 45 AND b.mood_code_id = 38)
   OR (a.mood_code_id = 48 AND b.mood_code_id = 35);
UPDATE onboarding_survey SET mood_code_id = 38 WHERE mood_code_id = 45;
UPDATE onboarding_survey SET mood_code_id = 35 WHERE mood_code_id = 48;

-- 2. 대분류 8종: 영문 내부 코드 + 한글 표시명.
UPDATE codes SET code_name = 'serene',    display_name = '잔잔한'   WHERE code_id = 32 AND code_type = 'MOOD';
UPDATE codes SET code_name = 'uplifting', display_name = '벅찬'     WHERE code_id = 33 AND code_type = 'MOOD';
UPDATE codes SET code_name = 'ethereal',  display_name = '몽환적인' WHERE code_id = 34 AND code_type = 'MOOD';
UPDATE codes SET code_name = 'energetic', display_name = '신나는'   WHERE code_id = 35 AND code_type = 'MOOD';
UPDATE codes SET code_name = 'focused',   display_name = '집중되는' WHERE code_id = 36 AND code_type = 'MOOD';
UPDATE codes SET code_name = 'emotional', display_name = '감성적인' WHERE code_id = 37 AND code_type = 'MOOD';
UPDATE codes SET code_name = 'intense',   display_name = '강렬한'   WHERE code_id = 38 AND code_type = 'MOOD';
UPDATE codes SET code_name = 'romantic',  display_name = '설레는'   WHERE code_id = 39 AND code_type = 'MOOD';

-- 3. taxonomy 9종: 표시명을 표준 한글명으로 (ai_server `TAXONOMY_MOOD_NAMES` 와 같은 값).
UPDATE codes SET display_name = '애상적인' WHERE code_id = 40 AND code_name = 'melancholic';
UPDATE codes SET display_name = '따뜻한'   WHERE code_id = 41 AND code_name = 'warm';
UPDATE codes SET display_name = '몽글한'   WHERE code_id = 42 AND code_name = 'dreamy';
UPDATE codes SET display_name = '화사한'   WHERE code_id = 43 AND code_name = 'bright';
UPDATE codes SET display_name = '희망찬'   WHERE code_id = 44 AND code_name = 'hopeful';
UPDATE codes SET display_name = '웅장한'   WHERE code_id = 45 AND code_name = 'powerful';
UPDATE codes SET display_name = '어두운'   WHERE code_id = 46 AND code_name = 'dark';
UPDATE codes SET display_name = '고요한'   WHERE code_id = 47 AND code_name = 'calm';
UPDATE codes SET display_name = '짜릿한'   WHERE code_id = 48 AND code_name = 'exciting';

-- 4. 중복 코드 삭제. 참조가 남아 있으면 FK 가 막는다 — 그게 맞다.
DELETE FROM codes WHERE code_type = 'MOOD' AND code_id BETWEEN 98 AND 106;

COMMIT;

-- 5. 확인.
SELECT code_id, code_name, display_name FROM codes WHERE code_type = 'MOOD' ORDER BY code_id;
--   기대: 32~48 의 17행. code_name 은 전부 영문, display_name 은 전부 한국어, 겹치는 표시명 없음.
SELECT mood_code_id, COUNT(*) FROM onboarding_survey GROUP BY mood_code_id ORDER BY mood_code_id;
--   기대: 45·48·100 이 없다.
SELECT user_id, mood_code_id, genre_code_id, COUNT(*) AS dup
FROM onboarding_survey GROUP BY 1, 2, 3 HAVING COUNT(*) > 1;
--   기대: 0행.
