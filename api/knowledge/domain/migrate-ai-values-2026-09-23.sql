-- 추천이 쓰는 오디오·생성 조건 값을 MySQL 로 옮긴다 (S15P21A204-423)
--
-- BPM·언어·스타일(곡)과 에너지(구간)는 지금까지 AI 색인 파일에만 있었다
-- (`track_features.parquet`, `moments.parquet` 의 `rms`). 그래서 추천 서버가 Spring
-- 카탈로그를 따로 당겨 와 색인과 합쳐야 했고, 그 구조에서 토큰 만료·파싱 불일치 장애가
-- 났다. 연동 계약 2026-09-23 절 — AI 는 Spring 을 부르지 않고, MySQL 값은 Spring 이
-- 추천 요청 본문으로 보낸다. 그러려면 그 값이 MySQL 에 있어야 한다.
--
-- 전부 분석 파이프라인이 잰 값이고 쓰는 쪽도 하나라 `music_analysis` 에 모은다.
--
-- `has_vocal` 은 NOT NULL 그대로다. 생성 음원은 생성 요청의 보컬 종류가 정답이라
-- 언제나 값이 있다 — 분류기가 `unknown`(확률 0.60 미만)을 낸 135곡은 전부 연주곡으로
-- 요청한 곡이었고 들어 보니 연주곡이었다. 적재 도구가 요청 값을 먼저 쓴다.
--
-- 전부 NULL 허용 컬럼 추가라 기존 행을 건드리지 않는다. 무중단으로 돌릴 수 있고,
-- 옛 코드는 새 컬럼을 모르고 지나간다. 03:00 백업 창을 피한다(decision 0011).
--
-- 값은 이 스크립트가 넣지 않는다. `ai_server/tools/backfill_ai_values.py` 가 색인에서
-- UPDATE 문을 만들어 준다 — 한 번 옮긴 뒤로는 적재 API 가 쓴다.

ALTER TABLE `music_analysis`
  ADD COLUMN `bpm` DOUBLE DEFAULT NULL COMMENT '측정 BPM' AFTER `vocal_gender`,
  ADD COLUMN `bpm_requested_min` SMALLINT DEFAULT NULL COMMENT '생성 때 요청한 BPM 범위 아래' AFTER `bpm`,
  ADD COLUMN `bpm_requested_max` SMALLINT DEFAULT NULL COMMENT '생성 때 요청한 BPM 범위 위' AFTER `bpm_requested_min`,
  ADD COLUMN `language` VARCHAR(32) DEFAULT NULL COMMENT '가사 언어' AFTER `bpm_requested_max`,
  ADD COLUMN `category` VARCHAR(64) DEFAULT NULL COMMENT '생성 스타일 원문' AFTER `language`;

ALTER TABLE `music_section_analysis`
  ADD COLUMN `energy` DOUBLE DEFAULT NULL COMMENT '구간 에너지 (오디오 RMS, 0~1)' AFTER `external_id`;

-- 확인
--   SHOW CREATE TABLE `music_analysis`;
--   SHOW CREATE TABLE `music_section_analysis`;
--
-- 되돌리기 (새 컬럼을 읽는 코드를 먼저 되돌린 뒤)
--   ALTER TABLE `music_section_analysis` DROP COLUMN `energy`;
--   ALTER TABLE `music_analysis`
--     DROP COLUMN `category`, DROP COLUMN `language`,
--     DROP COLUMN `bpm_requested_max`, DROP COLUMN `bpm_requested_min`, DROP COLUMN `bpm`;
