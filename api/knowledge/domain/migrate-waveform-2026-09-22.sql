-- 파형 저장을 위한 object_storage_files 확장 (S15P21A204-420)
--
-- 파형은 `music/{musicId}/waveform.json` 키로 오브젝트 스토리지에 올라가고,
-- 그 메타데이터가 이 테이블에 한 행으로 남는다. 막고 있던 것이 셋이었다.
--
--   file_type  파형은 이미지도 음원도 아니다. ENUM('IMG','MUSIC') 에 자리가 없었다.
--   category   용도가 청크(STREAMING)와 다르다. 청크는 배치만 읽지만 파형은
--              로그인한 사용자가 읽는다.
--   mime       `application/json` 이 16자라 VARCHAR(15) 에 들어가지 않는다.
--              넣으면 INSERT 가 잘리거나 깨진다 (StorageService 주석의 경고가 이것).
--
-- ENUM 에 값을 더하는 것은 기존 행을 건드리지 않고, VARCHAR 를 늘리는 것도
-- 데이터를 잃지 않는다. 그래서 무중단으로 돌릴 수 있다. 되돌리려면 같은 문장에서
-- 새 값과 길이만 빼면 되지만, 그 값을 쓰는 행이 이미 있으면 되돌아가지 않는다.

ALTER TABLE `object_storage_files`
  MODIFY COLUMN `file_type` ENUM('IMG', 'MUSIC', 'WAVEFORM') NOT NULL,
  MODIFY COLUMN `category`  ENUM('PROFILE', 'ARTIST', 'ALBUM', 'TRACK', 'MUBTI', 'STREAMING', 'WAVEFORM') NOT NULL,
  MODIFY COLUMN `mime`      VARCHAR(64) NOT NULL;

-- 확인
--   SELECT COLUMN_NAME, COLUMN_TYPE
--     FROM INFORMATION_SCHEMA.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME = 'object_storage_files'
--      AND COLUMN_NAME IN ('file_type', 'category', 'mime');


-- ── 곡 → 파형 파일 참조 ──────────────────────────────────────────────────────
--
-- 키(`music/{musicId}/waveform.json`)가 곡 번호에서 그대로 만들어지므로 컬럼 없이
-- `storage_object_key` 로 찾을 수도 있었다. 컬럼을 둔 이유는 셋이다.
--
--   1. 이 스키마의 파일 참조는 전부 FK 컬럼이다 (albums·artists·music·users·mubti
--      의 image_file_id, chunks.file_id). 파형만 키 규칙이면 `music` 을 봐도
--      이 곡에 파형이 있는지 알 수 없다.
--   2. 백필·감사가 "파형 없는 곡" 을 `WHERE waveform_file_id IS NULL` 로 뽑는다.
--      컬럼이 없으면 곡마다 조회를 한 번씩 때려 404 를 세야 한다.
--   3. `storage_object_key` 에는 인덱스가 없다 (PRIMARY 뿐). 청크 때문에 행이
--      곡당 수십 개씩 쌓이는 테이블이라 조회마다 풀스캔이 된다.
--
-- NULL 을 허용한다. 파형이 아직 없는 곡이 비정상이 아니다 — 프런트가 그때는
-- 기기에서 계산하는 예전 경로로 내려간다.
--
-- 재업로드에는 흔들리지 않는다. `StorageServiceImpl.uploadFile` 이 같은 키면
-- 기존 행을 재사용하므로 `file_id` 가 유지된다.

ALTER TABLE `music`
  ADD COLUMN `waveform_file_id` INT NULL COMMENT '웨이브폼 파일 번호' AFTER `image_file_id`;

ALTER TABLE `music`
  ADD CONSTRAINT `fk_music_waveform_file_id`
  FOREIGN KEY (`waveform_file_id`) REFERENCES `object_storage_files` (`file_id`);

-- 확인
--   SHOW CREATE TABLE `music`;
