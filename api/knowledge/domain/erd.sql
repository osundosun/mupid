-- Mupid ERD 스키마 (MySQL 8.4)
--
-- ERD 도구가 산출한 DDL 원문이다. 원칙적으로 손으로 고치지 않는다.
-- 스키마를 바꾸려면 ERD 를 먼저 고치고 산출물을 다시 받아 이 파일을 교체한다.
-- 다만 엔티티와 어긋난 것이 확인되면 산출물이 뒤처진 쪽이므로 엔티티를 기준으로 맞춘다
-- (playlist_tracks 는 그렇게 고쳤다).
-- 배경·규칙·코드와 어긋난 지점은 erd.md 를 본다.
--
-- **수기 보정 목록 — 산출물을 교체할 때마다 다시 확인한다.**
-- 도구가 아래 항목을 되돌릴 수 있다. 교체 후 살아 있는지 보고 없으면 다시 넣는다.
--   1. music_analysis_instruments.music_section_analysis_id 에 AUTO_INCREMENT 를 뺀다
--   2. music_section_analysis.external_id 에 UNIQUE 를 건다
--      (1·2 는 S15P21A204-344 / b6d5c7e)
--   3. ai_chat_sessions.ai_chat_session_id 에 AUTO_INCREMENT 를 넣는다
--      (AiChatSession 이 GenerationType.IDENTITY 다. 빼면 세션 생성이 죽는다)
--   4. music.title·artists.name·albums.title 의 일반/FULLTEXT ngram 인덱스를 유지한다
--      (통합 검색 성능 결정은 decision/0024)

CREATE TABLE IF NOT EXISTS `ai_chat_messages` (
  `ai_chat_message_id` BIGINT AUTO_INCREMENT NOT NULL,
  `session_id` INT NOT NULL,
  `index` INT NOT NULL,
  `message_type` ENUM('SYSTEM', 'USER', 'AI', 'ETC') NOT NULL DEFAULT "ETC",
  `message_id` VARCHAR(100),
  `message_name` TEXT,
  `text` TEXT,
  `content` JSON,
  `content_blocks` JSON,
  `additional_kwargs` JSON,
  `response_metadata` JSON,
  `music_id` INT,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`ai_chat_message_id`),
  CONSTRAINT `ck_ai_chat_messages_1` CHECK (`index`>=0)
);
CREATE TABLE IF NOT EXISTS `ai_chat_sessions` (
  `ai_chat_session_id` INT AUTO_INCREMENT NOT NULL,
  `title` VARCHAR(50) NOT NULL,
  `user_id` INT NOT NULL,
  `is_deleted` TINYINT(1) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`ai_chat_session_id`)
);
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
CREATE TABLE IF NOT EXISTS `albums` (
  `album_id` INT AUTO_INCREMENT NOT NULL COMMENT '앨범 PK',
  `artist_id` INT NOT NULL COMMENT '아티스트 FK',
  `title` VARCHAR(200) NOT NULL COMMENT '앨범명',
  `image_file_id` INT NOT NULL COMMENT '앨범 커버 이미지 파일 (object_storage_files)',
  `release_date` DATE NOT NULL COMMENT '발매일',
  `is_deleted` TINYINT(1) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`album_id`)
) COMMENT='앨범 정보';
CREATE INDEX `ix_albums_title` ON `albums` (`title`);
CREATE FULLTEXT INDEX `ft_albums_title_ngram` ON `albums` (`title`) WITH PARSER ngram;
CREATE TABLE IF NOT EXISTS `artists` (
  `artist_id` INT AUTO_INCREMENT NOT NULL COMMENT '아티스트 PK',
  `name` VARCHAR(200) NOT NULL COMMENT '아티스트명',
  `image_file_id` INT NOT NULL COMMENT '아티스트 이미지 파일 (object_storage_files)',
  `is_deleted` TINYINT(1) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`artist_id`)
) COMMENT='아티스트';
CREATE INDEX `ix_artists_name` ON `artists` (`name`);
CREATE FULLTEXT INDEX `ft_artists_name_ngram` ON `artists` (`name`) WITH PARSER ngram;
CREATE TABLE IF NOT EXISTS `chunks` (
  `stream_id` INT NOT NULL,
  `index` INT NOT NULL,
  `duration_ms` INT UNSIGNED NOT NULL,
  `file_id` INT NOT NULL,
  `file_hash` VARCHAR(64) NOT NULL COMMENT 'SHA-256',
  PRIMARY KEY (`stream_id`, `index`)
);
CREATE TABLE IF NOT EXISTS `codes` (
  `code_id` INT AUTO_INCREMENT NOT NULL,
  `code_type` ENUM('MOOD', 'INSTRUMENT', 'GENRE') NOT NULL,
  `code_name` VARCHAR(50) NOT NULL,
  `display_name` VARCHAR(200) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`code_id`)
);
CREATE UNIQUE INDEX `ix_codes_type_name` ON `codes` (`code_type`, `code_name`);
CREATE TABLE IF NOT EXISTS `daily_recommendation_tracks` (
  `daily_recommendation_id` INT NOT NULL COMMENT '오늘의 추천 FK',
  `rank_order` INT NOT NULL COMMENT '추천 순위',
  `music_id` INT NOT NULL COMMENT '음악 FK',
  `reason` VARCHAR(100) NOT NULL COMMENT '곡별 추천 사유',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
  PRIMARY KEY (`daily_recommendation_id`, `rank_order`)
) COMMENT='오늘의 모먼트 추천곡';
CREATE UNIQUE INDEX `uq_daily_recommendation_song` ON `daily_recommendation_tracks` (`daily_recommendation_id`, `music_id`);
CREATE TABLE IF NOT EXISTS `daily_recommendations` (
  `daily_recommendation_id` INT AUTO_INCREMENT NOT NULL COMMENT '오늘의 추천 PK',
  `user_id` INT NOT NULL COMMENT '유저 FK',
  `title` VARCHAR(100) NOT NULL COMMENT '모먼트 타이틀',
  `music_count` SMALLINT UNSIGNED NOT NULL,
  `reason` VARCHAR(100) NOT NULL COMMENT '추천 사유 문구 (AI 생성)',
  `mood_code_id` INT NOT NULL,
  `recommendation_date` DATE NOT NULL COMMENT '추천 기준일',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  PRIMARY KEY (`daily_recommendation_id`)
) COMMENT='오늘의 모먼트 추천';
CREATE UNIQUE INDEX `uq_daily_recommendation_user_date` ON `daily_recommendations` (`user_id`, `recommendation_date`);
CREATE TABLE IF NOT EXISTS `email_verifications` (
  `verification_id` INT AUTO_INCREMENT NOT NULL COMMENT '이메일 인증 PK',
  `user_id` INT,
  `email` VARCHAR(320) NOT NULL COMMENT '인증 대상 이메일',
  `token` VARCHAR(6) NOT NULL COMMENT '인증 토큰/코드',
  `purpose` ENUM('SIGNUP', 'PW_RESET') NOT NULL DEFAULT 'SIGNUP' COMMENT '용도 (SIGNUP)',
  `expired_at` DATETIME NOT NULL COMMENT '만료 일시',
  `verified_at` DATETIME COMMENT '인증 완료 일시 (POST /api/v1/auth/validation)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '발송 일시 (GET /api/v1/auth/validation)',
  PRIMARY KEY (`verification_id`)
) COMMENT='회원가입 이메일 인증';
CREATE UNIQUE INDEX `uq_email_verification_token` ON `email_verifications` (`token`);
CREATE TABLE IF NOT EXISTS `listening` (
  `report_id` INT NOT NULL,
  `music_id` INT NOT NULL,
  `listening_ms` INT UNSIGNED NOT NULL COMMENT 'Milliseconds 단위',
  PRIMARY KEY (`report_id`, `music_id`)
);
CREATE TABLE IF NOT EXISTS `lyrics` (
  `lyric_id` INT AUTO_INCREMENT NOT NULL,
  `music_id` INT NOT NULL,
  `text` VARCHAR(500) NOT NULL,
  `time_ms` INT UNSIGNED NOT NULL,
  PRIMARY KEY (`lyric_id`)
);
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
  PRIMARY KEY (`model_version_id`)
) COMMENT='모델 버전 (가중치 묶음)';
CREATE UNIQUE INDEX `uq_model_versions_name_version` ON `model_versions` (`name`, `version`);
CREATE TABLE IF NOT EXISTS `mubti` (
  `mubti_id` INT AUTO_INCREMENT NOT NULL,
  `title` VARCHAR(20) NOT NULL,
  `subtitle` VARCHAR(20) NOT NULL,
  `description` VARCHAR(150) NOT NULL,
  `class_a` ENUM('V', 'I') NOT NULL COMMENT '청취 포인트(보컬파, 멜로디파)',
  `class_b` ENUM('T', 'M') NOT NULL COMMENT '선곡 성향(트렌드형, 매니아형)',
  `class_c` ENUM('F', 'S') NOT NULL COMMENT '재생 방식(완주형, 스킵형)',
  `class_d` ENUM('C', 'E') NOT NULL COMMENT '탐색 성향(국밥형, 개척형)',
  `image_file_id` INT NOT NULL,
  PRIMARY KEY (`mubti_id`)
) COMMENT='사용자의 취향을 분류';
CREATE UNIQUE INDEX `ux_mubti_image_file_id` ON `mubti` (`image_file_id`);
CREATE UNIQUE INDEX `ux_mubti_title` ON `mubti` (`title`);
CREATE TABLE IF NOT EXISTS `music` (
  `music_id` INT AUTO_INCREMENT NOT NULL COMMENT '음악 PK',
  `album_id` INT NOT NULL COMMENT '앨범 FK',
  `artist_id` INT NOT NULL COMMENT '아티스트 FK',
  `title` VARCHAR(200) NOT NULL COMMENT '곡명',
  `release_date` DATE NOT NULL COMMENT '발매일',
  `play_count` INT NOT NULL DEFAULT 0 COMMENT '누적 재생 수',
  `like_count` INT NOT NULL DEFAULT 0 COMMENT '누적 좋아요 수',
  `image_file_id` INT COMMENT '썸네일 이미지 파일 번호',
  `waveform_file_id` INT COMMENT '웨이브폼 파일 번호',
  `is_deleted` TINYINT(1) NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`music_id`)
) COMMENT='음악 (메타데이터 조회 GET /api/v1/song/info)';
CREATE INDEX `ix_music_title` ON `music` (`title`);
CREATE FULLTEXT INDEX `ft_music_title_ngram` ON `music` (`title`) WITH PARSER ngram;
CREATE TABLE IF NOT EXISTS `music_analysis` (
  `music_id` INT NOT NULL,
  `has_vocal` TINYINT(1) NOT NULL,
  `vocal_gender` ENUM('M', 'F', 'MIX') DEFAULT NULL,
  `bpm` DOUBLE DEFAULT NULL COMMENT '측정 BPM',
  `bpm_requested_min` SMALLINT DEFAULT NULL COMMENT '생성 때 요청한 BPM 범위 아래',
  `bpm_requested_max` SMALLINT DEFAULT NULL COMMENT '생성 때 요청한 BPM 범위 위',
  `language` VARCHAR(32) DEFAULT NULL COMMENT '가사 언어',
  `category` VARCHAR(64) DEFAULT NULL COMMENT '생성 스타일 원문',
  PRIMARY KEY (`music_id`)
);
CREATE TABLE IF NOT EXISTS `music_analysis_instruments` (
  `music_section_analysis_id` INT NOT NULL,
  `instrument_code_id` INT NOT NULL,
  PRIMARY KEY (`music_section_analysis_id`, `instrument_code_id`)
);
CREATE TABLE IF NOT EXISTS `music_comments` (
  `music_comment_id` INT AUTO_INCREMENT NOT NULL COMMENT '댓글 PK',
  `music_id` INT NOT NULL COMMENT '음악 FK (?song={song_id})',
  `user_id` INT NOT NULL COMMENT '작성자 FK',
  `content` VARCHAR(100) NOT NULL COMMENT '댓글 내용',
  `timestamp_ms` INT UNSIGNED NOT NULL COMMENT '곡 내 타임스탬프(ms)',
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '삭제 여부',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '작성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
  PRIMARY KEY (`music_comment_id`)
) COMMENT='음악 타임스탬프 댓글';
CREATE TABLE IF NOT EXISTS `music_genres` (
  `music_id` INT NOT NULL COMMENT '음악 FK',
  `genre_code_id` INT NOT NULL,
  PRIMARY KEY (`music_id`, `genre_code_id`)
) COMMENT='음악-장르 매핑 (N:M)';
CREATE INDEX `uq_song_genre` ON `music_genres` (`music_id`);
CREATE TABLE IF NOT EXISTS `music_moods` (
  `music_id` INT NOT NULL COMMENT '음악 FK',
  `mood_code_id` INT NOT NULL,
  PRIMARY KEY (`music_id`, `mood_code_id`)
) COMMENT='음악-분위기 매핑 (N:M)';
CREATE INDEX `uq_song_mood` ON `music_moods` (`music_id`);
CREATE TABLE IF NOT EXISTS `music_reactions` (
  `user_id` INT NOT NULL COMMENT '유저 FK (복합키)',
  `music_id` INT NOT NULL COMMENT '음악 FK (복합키)',
  `reaction_type` ENUM('LIKE', 'DISLIKE') NOT NULL COMMENT '반응 (LIKE/DISLIKE) - 취소 시 행 삭제',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
  PRIMARY KEY (`user_id`, `music_id`)
) COMMENT='음악 좋아요/관심없음 (POST /api/v1/song/like)';
CREATE UNIQUE INDEX `ix_song_reactions_` ON `music_reactions` (`user_id`, `music_id`);
CREATE TABLE IF NOT EXISTS `music_section_analysis` (
  `music_section_analysis_id` INT AUTO_INCREMENT NOT NULL,
  `music_id` INT NOT NULL,
  `section_label` ENUM('INTRO', 'VERSE', 'PRE_CHORUS', 'CHORUS', 'BRIDGE', 'BREAK', 'INST', 'SOLO', 'OUTRO') NOT NULL,
  `index` INT NOT NULL,
  `start_time_ms` INT NOT NULL,
  `end_time_ms` INT NOT NULL,
  `external_id` VARCHAR(64) NOT NULL,
  `energy` DOUBLE DEFAULT NULL COMMENT '구간 에너지 (오디오 RMS, 0~1)',
  `analysis_version` VARCHAR(20) NOT NULL DEFAULT 'v1' COMMENT '구간 분석 버전',
  PRIMARY KEY (`music_section_analysis_id`)
);
CREATE UNIQUE INDEX `ux_music_section_analysis_external_id` ON `music_section_analysis` (`external_id`);
CREATE TABLE IF NOT EXISTS `music_section_analysis_mood` (
  `music_section_analysis_id` INT NOT NULL,
  `mood_code_id` INT NOT NULL,
  PRIMARY KEY (`music_section_analysis_id`, `mood_code_id`)
);
CREATE TABLE IF NOT EXISTS `object_storage_files` (
  `file_id` INT AUTO_INCREMENT NOT NULL,
  `file_type` ENUM('IMG', 'MUSIC', 'WAVEFORM', 'MODEL') NOT NULL,
  `category` ENUM('PROFILE', 'ARTIST', 'ALBUM', 'TRACK', 'MUBTI', 'STREAMING', 'WAVEFORM', 'MODEL') NOT NULL,
  `original_file_name` VARCHAR(255) NOT NULL,
  `storage_object_key` VARCHAR(255) NOT NULL,
  `file_size_bytes` INT NOT NULL COMMENT '파일 크기 (bytes)',
  `mime` VARCHAR(64) NOT NULL,
  `is_deleted` TINYINT(1) NOT NULL DEFAULT FALSE,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`file_id`)
) COMMENT='오브젝트 스토리지 파일';
CREATE TABLE IF NOT EXISTS `onboarding_survey` (
  `answer_id` INT AUTO_INCREMENT NOT NULL COMMENT '응답 PK',
  `user_id` INT NOT NULL COMMENT '유저 FK',
  `mood_code_id` INT NOT NULL,
  `genre_code_id` INT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
  PRIMARY KEY (`answer_id`)
) COMMENT='온보딩 문항별 응답';
CREATE TABLE IF NOT EXISTS `play_histories` (
  `play_history_id` BIGINT AUTO_INCREMENT NOT NULL,
  `user_id` INT NOT NULL COMMENT '유저 FK',
  `music_id` INT NOT NULL COMMENT '음악 FK',
  `selector` ENUM('USER', 'AI') NOT NULL,
  `recommendation_reason` VARCHAR(100),
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '재생 일시',
  PRIMARY KEY (`play_history_id`)
) COMMENT='최근 들은 음악 히스토리 (GET /api/v1/song/history)';
CREATE INDEX `ix_play_histories_` ON `play_histories` (`user_id`, `music_id`);
CREATE UNIQUE INDEX `ux_play_histories_play_history_id` ON `play_histories` (`play_history_id`);
CREATE TABLE IF NOT EXISTS `playlist_tracks` (
  `playlist_id` INT NOT NULL COMMENT '플레이리스트 FK',
  `track_order` SMALLINT UNSIGNED NOT NULL COMMENT '트랙 순서',
  `music_id` INT NOT NULL COMMENT '음악 FK',
  `selector` ENUM('USER', 'AI') NOT NULL,
  `user_id` INT COMMENT '추가한 유저 FK (NULL이면 시스템 생성)',
  `created_at` DATETIME NOT NULL COMMENT '추가 일시',
  PRIMARY KEY (`playlist_id`, `track_order`)
) COMMENT='플레이리스트 수록곡 (N:M)';
CREATE UNIQUE INDEX `uq_playlist_song` ON `playlist_tracks` (`playlist_id`, `music_id`);
CREATE TABLE IF NOT EXISTS `playlists` (
  `playlist_id` INT AUTO_INCREMENT NOT NULL COMMENT '플레이리스트 PK',
  `user_id` INT COMMENT '소유 유저 FK (NULL이면 시스템/추천 생성)',
  `title` VARCHAR(100) NOT NULL COMMENT '플레이리스트 제목',
  `music_count` SMALLINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '수록곡 수 (역정규화)',
  `deleted_at` DATETIME COMMENT '삭제 일시 (소프트 삭제)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
  PRIMARY KEY (`playlist_id`)
) COMMENT='플레이리스트 (목록/상세 조회 + 생성/수정/삭제)';
CREATE TABLE IF NOT EXISTS `reports` (
  `report_id` INT AUTO_INCREMENT NOT NULL COMMENT '분석 리포트 PK',
  `user_id` INT NOT NULL COMMENT '유저 FK',
  `start_date` DATE NOT NULL COMMENT '집계 시작일',
  `end_date` DATE NOT NULL COMMENT '집계 종료일',
  `mubti_id` INT NOT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  PRIMARY KEY (`report_id`)
) COMMENT='취향 분석 리포트';
CREATE UNIQUE INDEX `analysis_reports_copy_uq_analysis_report_period` ON `reports` (`user_id`, `start_date`);
CREATE TABLE IF NOT EXISTS `search_histories` (
  `search_history_id` INT AUTO_INCREMENT NOT NULL COMMENT '검색 기록 PK',
  `user_id` INT NOT NULL COMMENT '유저 FK',
  `keyword` VARCHAR(200) NOT NULL COMMENT '검색어',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '검색 일시',
  PRIMARY KEY (`search_history_id`)
) COMMENT='최근 검색어 (조회/삭제)';
CREATE TABLE IF NOT EXISTS `streams` (
  `stream_id` INT AUTO_INCREMENT NOT NULL,
  `music_id` INT NOT NULL,
  `bitrate_kbps` SMALLINT NOT NULL,
  `codec` ENUM('AAC', 'OPUS') NOT NULL,
  `duration_ms` INT UNSIGNED NOT NULL,
  `chunk_count` INT NOT NULL,
  `status` ENUM('PENDING', 'IN_PROGRESS', 'DONE', 'FAILED') NOT NULL DEFAULT 'PENDING',
  PRIMARY KEY (`stream_id`)
);
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
  PRIMARY KEY (`object_id`)
) COMMENT='배치용 Object Storage API 로 올리는 파일';
CREATE UNIQUE INDEX `uq_system_objects_storage_object_key` ON `system_objects` (`storage_object_key`);
CREATE INDEX `ix_system_objects_model_version_id` ON `system_objects` (`model_version_id`);
CREATE TABLE IF NOT EXISTS `users` (
  `user_id` INT AUTO_INCREMENT NOT NULL COMMENT '유저 PK',
  `email` VARCHAR(320) NOT NULL COMMENT '로그인 이메일',
  `password` VARCHAR(60) NOT NULL COMMENT '암호화된 비밀번호(BCrypt)',
  `nickname` VARCHAR(10) NOT NULL COMMENT '닉네임',
  `image_file_id` INT COMMENT '프로필 이미지 파일 (object_storage_files), 미설정 시 NULL',
  `birth_year` SMALLINT NOT NULL COMMENT '출생 연도',
  `gender` ENUM('M', 'F', 'ETC') NOT NULL DEFAULT 'ETC' COMMENT '성별 (M/F/ETC)',
  `role` ENUM('ROLE_USER', 'ROLE_SYSTEM', 'ROLE_ADMIN') NOT NULL COMMENT '유저 권한',
  `status` ENUM('PENDING', 'ACTIVE', 'WITHDRAW') NOT NULL DEFAULT 'PENDING' COMMENT '계정 상태 (PENDING/ACTIVE/WITHDRAW)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성 일시',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '수정 일시',
  PRIMARY KEY (`user_id`)
) COMMENT='회원';
CREATE INDEX `uq_user_email` ON `users` (`email`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `ai_chat_messages` ADD CONSTRAINT `fk_ai_chat_messages_session_id` FOREIGN KEY (`session_id`) REFERENCES `ai_chat_sessions` (`ai_chat_session_id`) ON DELETE CASCADE ON UPDATE CASCADE;
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `ai_chat_messages` ADD CONSTRAINT `fk_ai_chat_messages_song_id` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `ai_chat_sessions` ADD CONSTRAINT `fk_ai_chat_sessions_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`) ON DELETE CASCADE ON UPDATE CASCADE;
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `albums` ADD CONSTRAINT `fk_album_artist` FOREIGN KEY (`artist_id`) REFERENCES `artists` (`artist_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `albums` ADD CONSTRAINT `fk_album_cover_image` FOREIGN KEY (`image_file_id`) REFERENCES `object_storage_files` (`file_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `artists` ADD CONSTRAINT `fk_artist_image` FOREIGN KEY (`image_file_id`) REFERENCES `object_storage_files` (`file_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `chunks` ADD CONSTRAINT `fk_song_chunks_file_id` FOREIGN KEY (`file_id`) REFERENCES `object_storage_files` (`file_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `chunks` ADD CONSTRAINT `fk_song_chunks_song_stream_id` FOREIGN KEY (`stream_id`) REFERENCES `streams` (`stream_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `daily_recommendation_tracks` ADD CONSTRAINT `fk_daily_rec_song_recommendation` FOREIGN KEY (`daily_recommendation_id`) REFERENCES `daily_recommendations` (`daily_recommendation_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `daily_recommendation_tracks` ADD CONSTRAINT `fk_daily_rec_song_song` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `daily_recommendations` ADD CONSTRAINT `fk_daily_recommendation_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `daily_recommendations` ADD CONSTRAINT `fk_daily_recommendations_mood_code_id` FOREIGN KEY (`mood_code_id`) REFERENCES `codes` (`code_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `email_verifications` ADD CONSTRAINT `fk_email_verifications_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `listening` ADD CONSTRAINT `fk_listening_report_id` FOREIGN KEY (`report_id`) REFERENCES `reports` (`report_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `listening` ADD CONSTRAINT `fk_report_listening_music_id` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `lyrics` ADD CONSTRAINT `fk_song_lyrics_song_id` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `mubti` ADD CONSTRAINT `fk_mubti_file_id` FOREIGN KEY (`image_file_id`) REFERENCES `object_storage_files` (`file_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music` ADD CONSTRAINT `fk_music_image_file_id` FOREIGN KEY (`image_file_id`) REFERENCES `object_storage_files` (`file_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music` ADD CONSTRAINT `fk_music_waveform_file_id` FOREIGN KEY (`waveform_file_id`) REFERENCES `object_storage_files` (`file_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music` ADD CONSTRAINT `fk_song_album` FOREIGN KEY (`album_id`) REFERENCES `albums` (`album_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music` ADD CONSTRAINT `fk_song_artist` FOREIGN KEY (`artist_id`) REFERENCES `artists` (`artist_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_analysis` ADD CONSTRAINT `fk_song_analysis_song_id` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_analysis_instruments` ADD CONSTRAINT `fk_music_analysis_instruments_code_id` FOREIGN KEY (`instrument_code_id`) REFERENCES `codes` (`code_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_analysis_instruments` ADD CONSTRAINT `fk_music_analysis_instruments_music_section_analysis_id` FOREIGN KEY (`music_section_analysis_id`) REFERENCES `music_section_analysis` (`music_section_analysis_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_comments` ADD CONSTRAINT `fk_comment_song` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_comments` ADD CONSTRAINT `fk_comment_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_genres` ADD CONSTRAINT `fk_music_genres_code_id` FOREIGN KEY (`genre_code_id`) REFERENCES `codes` (`code_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_genres` ADD CONSTRAINT `fk_song_genre_song` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_moods` ADD CONSTRAINT `fk_music_moods_code_id` FOREIGN KEY (`mood_code_id`) REFERENCES `codes` (`code_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_moods` ADD CONSTRAINT `fk_song_mood_song` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_reactions` ADD CONSTRAINT `fk_song_reaction_song` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_reactions` ADD CONSTRAINT `fk_song_reaction_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_section_analysis` ADD CONSTRAINT `fk_song_section_analysis_song_id` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_section_analysis_mood` ADD CONSTRAINT `fk_music_section_analysis_mood_code_id` FOREIGN KEY (`mood_code_id`) REFERENCES `codes` (`code_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `music_section_analysis_mood` ADD CONSTRAINT `fk_song_section_analysis_mood_song_section_id` FOREIGN KEY (`music_section_analysis_id`) REFERENCES `music_section_analysis` (`music_section_analysis_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `onboarding_survey` ADD CONSTRAINT `fk_onboarding_answers_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `onboarding_survey` ADD CONSTRAINT `fk_onboarding_survey_code_id` FOREIGN KEY (`genre_code_id`) REFERENCES `codes` (`code_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `onboarding_survey` ADD CONSTRAINT `fk_onboarding_survey_mood_code_id` FOREIGN KEY (`mood_code_id`) REFERENCES `codes` (`code_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `play_histories` ADD CONSTRAINT `fk_play_history_song` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `play_histories` ADD CONSTRAINT `fk_play_history_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `playlist_tracks` ADD CONSTRAINT `fk_playlist_song_playlist` FOREIGN KEY (`playlist_id`) REFERENCES `playlists` (`playlist_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `playlist_tracks` ADD CONSTRAINT `fk_playlist_song_song` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `playlist_tracks` ADD CONSTRAINT `fk_playlist_song_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `playlists` ADD CONSTRAINT `fk_playlist_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `reports` ADD CONSTRAINT `analysis_reports_copy_fk_analysis_report_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `reports` ADD CONSTRAINT `fk_analysis_reports_copy_listening_dna_id` FOREIGN KEY (`mubti_id`) REFERENCES `mubti` (`mubti_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `search_histories` ADD CONSTRAINT `fk_search_history_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`user_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `streams` ADD CONSTRAINT `fk_song_streams_song_id` FOREIGN KEY (`music_id`) REFERENCES `music` (`music_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `system_objects` ADD CONSTRAINT `fk_system_objects_model_version_id` FOREIGN KEY (`model_version_id`) REFERENCES `model_versions` (`model_version_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `system_objects` ADD CONSTRAINT `fk_system_objects_file_id` FOREIGN KEY (`file_id`) REFERENCES `object_storage_files` (`file_id`);
-- 참조 무결성을 위반하는 기존 데이터가 있으면 실패합니다
ALTER TABLE `users` ADD CONSTRAINT `fk_user_profile_image` FOREIGN KEY (`image_file_id`) REFERENCES `object_storage_files` (`file_id`);
