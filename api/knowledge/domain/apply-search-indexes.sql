-- 기존 MySQL DB에 검색 인덱스를 한 번 적용한다.
-- 신규 DB는 erd.sql에 같은 인덱스가 있으므로 이 파일을 다시 실행하지 않는다.
ALTER TABLE `music`
  ADD INDEX `ix_music_title` (`title`),
  ADD FULLTEXT INDEX `ft_music_title_ngram` (`title`) WITH PARSER ngram;
ALTER TABLE `artists`
  ADD INDEX `ix_artists_name` (`name`),
  ADD FULLTEXT INDEX `ft_artists_name_ngram` (`name`) WITH PARSER ngram;
ALTER TABLE `albums`
  ADD INDEX `ix_albums_title` (`title`),
  ADD FULLTEXT INDEX `ft_albums_title_ngram` (`title`) WITH PARSER ngram;
