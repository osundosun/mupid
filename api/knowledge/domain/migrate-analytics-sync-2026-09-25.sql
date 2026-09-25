-- MySQL–ClickHouse 정합성 기반 (S15P21A204-451, 저장소 재설계 §8)
--
-- 구간을 MySQL 에 넣고 커밋한 뒤 ClickHouse `track_sections_local` 에도 넣는데, 그 쓰기가
-- 실패하면 지금까지는 로그만 남았다. 그 곡은 재생 신호가 조용히 버려지고, 사람이
-- `push_sections.py` 로 메워야 했다(그 도구는 ClickHouse 에 직접 쓴다 — 쓰기 관문 우회).
--
-- 1. `analytics_sync_pending` — 구간 교체와 **같은 트랜잭션에서** "아직 ClickHouse 에 안
--    갔다" 를 남긴다. 커밋과 한 몸이라 빠지지 않는다. 커밋 뒤 쓰기가 성공하면 지우고,
--    실패하면 남은 행을 Spring 재시도 작업(1분)이 곡 단위로 다시 쓴다. 곡·종류마다 한 행이다.
--    `kind` 를 ENUM 이 아니라 VARCHAR 로 둔 것은 다음 종류(`VECTOR`, 457)를 DDL 없이 더하려는 것이다.
--    `revision` 은 같은 곡이 다시 올 때마다 1 씩 오른다. 커밋 뒤 쓰기는 **자기가 남긴 revision 일 때만**
--    행을 지운다 — 앞선 요청의 성공이 뒤 요청의 미반영을 지우면 그 곡이 빠진다.
-- 2. `music_section_analysis.analysis_version` — 지금까지 요청이 실어 온 값이 ClickHouse 에만
--    남았다. 재시도·대조가 MySQL 만 보고 같은 값을 쓰려면 MySQL 에 있어야 한다. 기존 행은
--    전부 `v1` 로 적재됐다(2026-09-25 대조: 9,321행 모두 `v1`).
--
-- NOT NULL DEFAULT 가 있는 컬럼 추가와 새 표뿐이라 기존 행을 깨지 않는다. **앱보다 먼저** 돌린다 —
-- 새 앱은 기동 뒤 1분마다 새 표를 읽고, 구간 적재가 새 컬럼에 쓴다. 03:00 백업 창을 피한다(decision 0011).

CREATE TABLE IF NOT EXISTS `analytics_sync_pending` (
  `music_id` INT NOT NULL COMMENT '곡 번호 (music.music_id)',
  `kind` VARCHAR(16) NOT NULL COMMENT '반영할 곳 — CLICKHOUSE',
  `analysis_version` VARCHAR(20) NOT NULL COMMENT '반영할 분석 버전',
  `revision` BIGINT NOT NULL DEFAULT 1 COMMENT '같은 곡이 다시 기록될 때마다 오른다',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '재시도 작업이 시도한 횟수',
  `last_error` VARCHAR(500) DEFAULT NULL COMMENT '마지막 실패 사유',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '처음 미반영이 된 시각',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '마지막으로 기록·시도한 시각',
  PRIMARY KEY (`music_id`, `kind`)
) COMMENT='ClickHouse 등 사본에 아직 반영되지 않은 곡';

ALTER TABLE `music_section_analysis`
  ADD COLUMN `analysis_version` VARCHAR(20) NOT NULL DEFAULT 'v1' COMMENT '구간 분석 버전' AFTER `energy`;

-- 확인
--   SHOW CREATE TABLE `analytics_sync_pending`;
--   SELECT analysis_version, COUNT(*) FROM `music_section_analysis` GROUP BY analysis_version;
--
-- 되돌리기 (새 표·컬럼을 쓰는 앱을 먼저 되돌린 뒤)
--   ALTER TABLE `music_section_analysis` DROP COLUMN `analysis_version`;
--   DROP TABLE `analytics_sync_pending`;
