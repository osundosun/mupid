-- 배치용 Object Storage API — 모델 가중치 (S15P21A204-453, 저장소 재설계 §6.3·§6.5)
--
-- 모델 묶음 파일을 `system/model/{name}/{version}/{file}` 키로 오브젝트 스토리지에 올리고,
-- CI 가 받아 가게 한다. 음악 청크와 같은 3단계(요청 → Nginx 직행 업로드 → 완료)라서
-- 스프링에는 "업로드 중" 을 적어 둘 자리와 버전 상태를 둘 자리가 필요했다.
--
-- 1. `object_storage_files` 의 `file_type`·`category` 에 `MODEL` — 완료된 파일의 메타가 이
--    표에 한 행으로 남는다. 가중치는 이미지·음원·파형 어느 것도 아니다. 파형(`WAVEFORM`)을
--    넣은 `migrate-waveform-2026-09-22.sql` 과 같은 절차 — ENUM 끝에 값을 더하는 것은 기존
--    행을 건드리지 않는다.
-- 2. `model_versions` — 이름·버전마다 한 행. `UPLOADING → CANDIDATE → ADOPTED → RETIRED`.
--    `file_names` 는 첫 업로드 요청이 정한 파일 목록이다(정렬·쉼표). 모두 완료되면 후보가 된다.
-- 3. `system_objects` — 파일 하나. ① 요청 때 신고한 크기·sha256 으로 자리를 잡고(`UPLOADING`),
--    ③ 완료 때 서버가 스토리지를 흘려 읽어 sha256 을 확인하면 `COMPLETED` 와 `file_id` 가 찬다.
--    `storage_object_key` 가 유일하다 — 같은 이름·버전·파일명은 한 행이고, 다른 sha256 은
--    앱이 거절한다(불변).
--
-- 새 ENUM 값과 새 표뿐이라 기존 행을 깨지 않는다. **앱보다 먼저** 돌린다 — 새 앱은 엔티티가
-- 이 표들을 가리키고, 없는 ENUM 값을 쓰면 `Data truncated` 가 난다(ddl-auto: none).
-- 03:00 백업 창을 피한다(decision 0011).

ALTER TABLE `object_storage_files`
  MODIFY COLUMN `file_type` ENUM('IMG', 'MUSIC', 'WAVEFORM', 'MODEL') NOT NULL,
  MODIFY COLUMN `category`  ENUM('PROFILE', 'ARTIST', 'ALBUM', 'TRACK', 'MUBTI', 'STREAMING', 'WAVEFORM', 'MODEL') NOT NULL;

CREATE TABLE IF NOT EXISTS `model_versions` (
  `model_version_id` INT AUTO_INCREMENT NOT NULL COMMENT '모델 버전 PK',
  `name` VARCHAR(32) NOT NULL COMMENT '모델 이름 (예: stage1)',
  `version` VARCHAR(16) NOT NULL COMMENT '버전 (예: v3)',
  `status` ENUM('UPLOADING', 'CANDIDATE', 'ADOPTED', 'RETIRED') NOT NULL COMMENT '상태',
  `file_names` VARCHAR(2048) NOT NULL COMMENT '이 버전의 파일명 목록 (정렬, 쉼표)',
  `manifest_sha256` VARCHAR(64) DEFAULT NULL COMMENT 'manifest.json 의 sha256 — 후보가 될 때 채운다',
  `candidate_at` DATETIME DEFAULT NULL COMMENT '후보가 된 시각',
  `adopted_at` DATETIME DEFAULT NULL COMMENT '마지막으로 채택된 시각',
  `retired_at` DATETIME DEFAULT NULL COMMENT '폐기된 시각',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
  PRIMARY KEY (`model_version_id`),
  UNIQUE KEY `uq_model_versions_name_version` (`name`, `version`)
) COMMENT='모델 버전 (가중치 묶음)';

CREATE TABLE IF NOT EXISTS `system_objects` (
  `object_id` INT AUTO_INCREMENT NOT NULL COMMENT '오브젝트 PK',
  `kind` ENUM('MODEL') NOT NULL COMMENT '종류',
  `model_version_id` INT DEFAULT NULL COMMENT '모델 버전 (kind = MODEL)',
  `file_name` VARCHAR(128) NOT NULL COMMENT '파일명',
  `storage_object_key` VARCHAR(255) NOT NULL COMMENT '저장 키 — system/model/{name}/{version}/{file}',
  `size_bytes` BIGINT NOT NULL COMMENT '신고한 크기 (bytes)',
  `sha256` VARCHAR(64) NOT NULL COMMENT '신고한 sha256 — 완료 때 서버가 대조한다',
  `status` ENUM('UPLOADING', 'COMPLETED') NOT NULL COMMENT '상태',
  `file_id` INT DEFAULT NULL COMMENT '완료 뒤 스토리지 파일 (object_storage_files)',
  `completed_at` DATETIME DEFAULT NULL COMMENT '완료된 시각',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
  PRIMARY KEY (`object_id`),
  UNIQUE KEY `uq_system_objects_storage_object_key` (`storage_object_key`),
  KEY `ix_system_objects_model_version_id` (`model_version_id`),
  CONSTRAINT `fk_system_objects_model_version_id`
    FOREIGN KEY (`model_version_id`) REFERENCES `model_versions` (`model_version_id`),
  CONSTRAINT `fk_system_objects_file_id`
    FOREIGN KEY (`file_id`) REFERENCES `object_storage_files` (`file_id`)
) COMMENT='배치용 Object Storage API 로 올리는 파일';

-- 확인
--   SELECT COLUMN_NAME, COLUMN_TYPE
--     FROM INFORMATION_SCHEMA.COLUMNS
--    WHERE TABLE_SCHEMA = DATABASE()
--      AND TABLE_NAME = 'object_storage_files'
--      AND COLUMN_NAME IN ('file_type', 'category');
--   SHOW CREATE TABLE `model_versions`;
--   SHOW CREATE TABLE `system_objects`;
--
-- 되돌리기 (새 표를 쓰는 앱을 먼저 되돌린 뒤. MODEL 행이 있으면 ENUM 은 되돌아가지 않는다)
--   DROP TABLE `system_objects`;
--   DROP TABLE `model_versions`;
--   ALTER TABLE `object_storage_files`
--     MODIFY COLUMN `file_type` ENUM('IMG', 'MUSIC', 'WAVEFORM') NOT NULL,
--     MODIFY COLUMN `category`  ENUM('PROFILE', 'ARTIST', 'ALBUM', 'TRACK', 'MUBTI', 'STREAMING', 'WAVEFORM') NOT NULL;
