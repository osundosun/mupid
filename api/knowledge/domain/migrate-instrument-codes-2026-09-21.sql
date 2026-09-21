-- 운영 DB `codes(INSTRUMENT)` 를 AI 레이블에 맞춘다 (2026-09-21).
--
-- 무엇이 문제인가: `code_name` 이 한국어다. 규칙은 <`code_name` = AI 가 붙이는 영문
-- 레이블, `display_name` = 화면용 한국어> 이고, 분위기 17종·장르 45종은 그 방향이다.
-- 악기 4종만 반대로 들어가 있다.
--
--   94 드럼        → drums
--   95 그 외 악기   → other
--   96 보컬        → vocals
--   97 베이스      → bass
--
-- 영문 이름의 출처는 demucs 스템 이름이다
-- (`music_preprocessing/src/music_preprocessing/analysis/instruments.py` 의 `STEMS`).
--
-- **번호는 그대로 둔다.** `music_analysis_instruments` 30,224행이 94~97 을 참조하므로
-- 이름만 바꾸면 참조는 건드릴 필요가 없다. 중복 코드도 없다.
--
-- ⚠ **반드시 배포와 함께 실행한다.** 먼저 실행하면 안 된다 —
--   · 구간 적재 배치(`ai_server/tools/register_sections.py`)가 아직 한국어를 보내는
--     판이면 `POST /api/v1/code` 가 `드럼` 을 **새 코드로 다시 만든다.**
--   · 2026-09-21 에 분위기에서 이 일이 두 번 일어났다 (98~106, 107~123).
--     운영 API 가 재기동되면서 배포 전 시드가 한국어 이름을 다시 넣었다.
--   그래서 순서는 <배포 → 이 SQL> 이다. 반대로 하지 않는다.
--
-- 실행: MySQL Workbench 에서 `mupid` 스키마에 통째로 실행한다. 클라이언트 문자셋은 utf8mb4.

START TRANSACTION;

-- 0. 전제 확인. 0 이 아니면 멈추고 상태를 다시 본다.
SELECT COUNT(*) AS must_be_0_english_names_taken
FROM codes WHERE code_type = 'INSTRUMENT'
  AND code_name IN ('vocals', 'drums', 'bass', 'other');

-- 1. 영문 코드 이름 + 한글 표시명.
UPDATE codes SET code_name = 'drums',  display_name = '드럼'      WHERE code_id = 94 AND code_type = 'INSTRUMENT';
UPDATE codes SET code_name = 'other',  display_name = '그 외 악기' WHERE code_id = 95 AND code_type = 'INSTRUMENT';
UPDATE codes SET code_name = 'vocals', display_name = '보컬'      WHERE code_id = 96 AND code_type = 'INSTRUMENT';
UPDATE codes SET code_name = 'bass',   display_name = '베이스'    WHERE code_id = 97 AND code_type = 'INSTRUMENT';

COMMIT;

-- 2. 확인.
SELECT code_id, code_name, display_name FROM codes WHERE code_type = 'INSTRUMENT' ORDER BY code_id;
--   기대: 94 drums/드럼, 95 other/그 외 악기, 96 vocals/보컬, 97 bass/베이스.
SELECT COUNT(*) AS links_unchanged FROM music_analysis_instruments;
--   기대: 30224 (이름만 바꿨으므로 그대로).
SELECT COUNT(*) AS must_be_0_korean_code_names
FROM codes WHERE code_type = 'INSTRUMENT' AND code_name REGEXP '[가-힣]';
--   기대: 0. 0 이 아니면 배치가 한국어 코드를 다시 만든 것이다 — 배포 판을 확인한다.
