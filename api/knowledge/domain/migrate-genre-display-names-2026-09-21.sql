-- `codes(GENRE)` 대분류 8종에 화면용 한국어를 채운다 (2026-09-21).
--
-- 무엇이 문제인가: 대분류 8종(code_id 9~16)의 `display_name` 이 빈 문자열이다.
-- 화면은 `display_name || code_name` 을 그리므로(`CodeOption.of`) 이 여덟 개만
-- 영문으로 뜬다 — 세분류 45종은 한국어라 **온보딩 목록에 두 언어가 섞인다.**
--
-- 그리고 `R&B`(11) 는 세분류 `rnb`(89) 의 표시명(`R&B`)과 글자가 같아
-- **같은 칩이 둘로 보였다.** 분위기에서 `강렬한`·`신나는` 이 겹쳤던 것과 같은 모양이다.
-- 대분류 쪽을 `알앤비` 로 갈라 둔다.
--
-- **`code_name` 은 건드리지 않는다.** AI 서버가 이 철자를 세분류에 이름 포함으로 잇고
-- (`ai_server/app/onboarding.py` 의 `map_genre` · `GENRE_EXTRA`), 이미 영문이라
-- <`code_name` = 영문, `display_name` = 한국어> 규칙에 이미 맞다. 바꾸면 그 사용자의
-- 장르가 추천에 조용히 반영되지 않는다.
--
-- 그래서 **이 SQL 은 배포 순서를 가리지 않는다.** 분위기·악기 때와 다른 점이다 —
-- 옛 판의 시드도 `code_name` 으로 찾으므로 이 변경 뒤에도 그대로 찾아내 다시 넣지
-- 않는다. 분위기에서 고아 17행이 두 번 생긴 것은 `code_name` 을 바꿨기 때문이다.
--
-- 실행: MySQL Workbench 에서 `mupid` 스키마에 통째로. 클라이언트 문자셋은 utf8mb4.

START TRANSACTION;

UPDATE codes SET display_name = '일렉트로닉' WHERE code_type = 'GENRE' AND code_name = 'Electronic';
UPDATE codes SET display_name = '인디'       WHERE code_type = 'GENRE' AND code_name = 'Indie';
UPDATE codes SET display_name = '알앤비'     WHERE code_type = 'GENRE' AND code_name = 'R&B';
UPDATE codes SET display_name = '록'         WHERE code_type = 'GENRE' AND code_name = 'Rock';
UPDATE codes SET display_name = '팝'         WHERE code_type = 'GENRE' AND code_name = 'Pop';
UPDATE codes SET display_name = '힙합'       WHERE code_type = 'GENRE' AND code_name = 'Hip-hop';
UPDATE codes SET display_name = '재즈'       WHERE code_type = 'GENRE' AND code_name = 'Jazz';
UPDATE codes SET display_name = '클래식'     WHERE code_type = 'GENRE' AND code_name = 'Classical';

COMMIT;

-- 확인.
SELECT COUNT(*) AS must_be_0_blank_display
FROM codes WHERE display_name IS NULL OR display_name = '';
--   기대: 0. 어떤 종류든 표시명이 비면 화면이 코드 이름으로 되돌아간다.

SELECT display_name, COUNT(*) AS c
FROM codes WHERE code_type = 'GENRE'
GROUP BY display_name HAVING COUNT(*) > 1;
--   기대: 0행. 같은 글자가 두 칩으로 뜨면 사용자는 어느 쪽을 눌러야 할지 모른다.

SELECT code_id, code_name, display_name FROM codes WHERE code_type = 'GENRE' AND code_id <= 16 ORDER BY code_id;
