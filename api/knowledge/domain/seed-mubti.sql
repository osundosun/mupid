-- MuBTI 16유형(mubti) 기준 데이터.
--
-- `ddl-auto` 가 `none` 이라 스키마도 데이터도 사람이 직접 넣는다
-- (decision/0011). **이 표가 비어 있으면 취향 리포트가 503
-- (`MUPID_REPORT_MUBTI_NOT_SEEDED`) 으로 막힌다.** 온보딩이 `codes` 표에 걸리는
-- 것과 같은 구조다.
--
-- 적용:
--   docker exec -i mupid-dev-mysql mysql --default-character-set=utf8mb4 \
--     -u<USER> -p<PASSWORD> mupid < seed-mubti.sql
--
-- **`--default-character-set=utf8mb4` 를 빼면 안 된다.** 클라이언트가 이 파일의
-- UTF-8 바이트를 latin1 로 읽어 다시 인코딩해서, 한글이 깨진 채로 들어간다.
-- 오류가 나지 않아 조용히 성공한다 — `SELECT CHAR_LENGTH(title)` 이 글자 수보다
-- 크면(예: `보컬 소개팅형` 이 7 이 아니라 19) 그렇게 들어간 것이다.
--
-- 네 축의 뜻과 판정 근거는 knowledge/domain/mubti-report.md 에 있다.
--
--   classa  V 보컬파   / I 멜로디파     무엇을 중심으로 듣는가
--   classb  T 트렌드형 / M 매니아형     어떻게 고르는가
--   classc  F 완주형   / S 스킵형       어떻게 재생하는가
--   classd  C 국밥형   / E 개척형       얼마나 새로 찾는가
--
-- `title` 은 VARCHAR(20), `subtitle` 은 VARCHAR(50), `description` 은
-- VARCHAR(100) 이다. 길이를 넘기면 그 행만 조용히 잘리거나 1406 으로 막힌다.
--
-- `image_file_id` 는 NULL 로 둔다. 유형 일러스트가 준비되면 채운다 —
-- `uq_mubti_image` 가 UNIQUE 라 같은 파일을 두 유형에 붙일 수 없다.
--
-- 여러 번 돌려도 늘어나지 않는다. 네 축 조합이 UNIQUE 제약으로 걸려 있지 않아서
-- INSERT ... SELECT 로 있는지 보고 넣는다.
INSERT INTO `mubti` (`title`, `subtitle`, `description`, `classa`, `classb`, `classc`, `classd`)
SELECT wanted.t, wanted.s, wanted.d, wanted.a, wanted.b, wanted.c, wanted.e FROM (
  SELECT '보컬 인싸형' AS t,
         '모두의 보컬은 곧 나의 최애' AS s,
         '지금 사랑받는 목소리를 끝까지 듣고, 마음에 든 곡은 오래 곁에 둬요.' AS d,
         'V' AS a, 'T' AS b, 'F' AS c, 'C' AS e
  UNION ALL SELECT '보컬 소개팅형', '뜨는 목소리는 끝까지 들어보는',
         '새로 뜨는 보컬이 보이면 일단 처음부터 끝까지 들어보고 판단해요.',
         'V', 'T', 'F', 'E'
  UNION ALL SELECT '보컬 뚝심형', '인싸지만 나만의 색이 있는',
         '인기곡을 따라가면서도 안 꽂히면 바로 넘기고, 최애는 좀처럼 안 바뀌어요.',
         'V', 'T', 'S', 'C'
  UNION ALL SELECT '보컬 오디션형', '네 다음 보컬 나와주세요',
         '새 목소리를 빠르게 훑어보며 마음에 드는 한 소절을 찾아다녀요.',
         'V', 'T', 'S', 'E'
  UNION ALL SELECT '보컬 순애형', '최애 숨소리까지 음악이다',
         '좋아하는 목소리 하나를 깊게 파고, 처음부터 끝까지 몇 번이고 들어요.',
         'V', 'M', 'F', 'C'
  UNION ALL SELECT '보컬 발굴가형', '원석같은 보컬은 끝까지 파보는',
         '아직 알려지지 않은 목소리를 찾아내 끝까지 들어보는 편이에요.',
         'V', 'M', 'F', 'E'
  UNION ALL SELECT '보컬 철벽형', '나의 보컬 취향은 바꿀 수 없어',
         '취향에 맞는 목소리만 남기고 나머지는 빠르게 넘겨요. 최애는 그대로예요.',
         'V', 'M', 'S', 'C'
  UNION ALL SELECT '보컬 감별사형', '한 소절 만에 감별해 드립니다',
         '낯선 곡도 한 소절이면 충분해요. 아니다 싶으면 바로 다음 곡으로 넘어가요.',
         'V', 'M', 'S', 'E'
  UNION ALL SELECT '멜로디 인싸형', '모두가 듣는 순간은 이유가 있다',
         '요즘 사랑받는 사운드를 끝까지 듣고, 좋았던 곡은 계속 다시 틀어요.',
         'I', 'T', 'F', 'C'
  UNION ALL SELECT '멜로디 소개팅형', '새로 뜨는 사운드는 언제나 환영',
         '새로 나온 사운드가 궁금해서 일단 한 곡을 통째로 들어보는 편이에요.',
         'I', 'T', 'F', 'E'
  UNION ALL SELECT '멜로디 뚝심형', '유명한 사운드도 안 꽂히면 싫어',
         '인기곡이라도 사운드가 안 맞으면 바로 넘기고, 듣던 곡으로 돌아와요.',
         'I', 'T', 'S', 'C'
  UNION ALL SELECT '멜로디 오디션형', '네 다음 사운드 나와주세요',
         '새로운 사운드를 빠르게 넘겨보며 꽂히는 구간을 찾아다녀요.',
         'I', 'T', 'S', 'E'
  UNION ALL SELECT '멜로디 순애형', '나만의 작고 소중한 사운드',
         '좋아하는 사운드 결을 깊게 파고, 한 곡을 오래 반복해서 들어요.',
         'I', 'M', 'F', 'C'
  UNION ALL SELECT '멜로디 모험가형', '인생 사운드를 찾아 떠나는',
         '아직 모르는 사운드를 찾아 끝까지 들어보며 인생 곡을 늘려가요.',
         'I', 'M', 'F', 'E'
  UNION ALL SELECT '멜로디 철벽형', '나의 사운드 취향은 바꿀 수 없어',
         '취향에 맞는 사운드만 남기고 나머지는 빠르게 넘겨요. 결이 확고해요.',
         'I', 'M', 'S', 'C'
  UNION ALL SELECT '멜로디 감별사형', '한 마디 만에 감별해 드립니다',
         '낯선 곡도 한 마디면 알아봐요. 아니면 바로 다음 사운드로 넘어가요.',
         'I', 'M', 'S', 'E'
) AS wanted
WHERE NOT EXISTS (
  SELECT 1 FROM `mubti` m
  WHERE m.`classa` = wanted.a AND m.`classb` = wanted.b
    AND m.`classc` = wanted.c AND m.`classd` = wanted.e
);
