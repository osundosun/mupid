/**
 * 청취 이벤트 계약. `api/clickhouse/init/01-schema.sql` 의 `listen_events` 컬럼과 1:1 이다.
 *
 * 여기에 없는 것 두 가지가 설계의 핵심이다.
 *
 *   user_id  서버가 JWT 에서 확정한다. 클라이언트가 보내면 위조된다.
 *   weight   수집 단계에는 가중치가 없다. 어떤 행동이 얼마나 긍정인지는 분석·모델의
 *            판단이고, 규칙이 바뀌면 원본부터 다시 계산한다.
 *
 * 구간(section) 번호도 보내지 않는다. 밀리초만 보내고 구간 매핑은 백엔드가 한다.
 */

export type EventType =
  | 'PLAY_START'
  | 'PLAY_PROGRESS'
  | 'PLAY_PAUSE'
  | 'PLAY_RESUME'
  | 'PLAY_END'
  | 'PLAY_STALL'
  | 'PLAY_ERROR'
  | 'PLAYBACK_ABANDON'
  | 'SEEK_BACK'
  | 'SEEK_FORWARD'
  | 'TRACK_SKIP'
  | 'LIKE'
  | 'UNLIKE'
  | 'DISLIKE'
  | 'UNDISLIKE'
  | 'SECTION_MARK'
  | 'SECTION_UNMARK'
  | 'PLAYLIST_ADD'
  | 'PLAYLIST_REMOVE'
  | 'COMMENT_ADD'
  | 'COMMENT_DELETE'
  | 'SEARCH';

/**
 * 이 재생이 어디서 왔는가.
 *
 * `RECOMMEND` 는 **추천을 보고 골랐다** 는 뜻이지 화면의 기본값이 아니다. 재생 목록에서
 * 다시 꺼낸 곡(`QUEUE`)과 최근 들은 음악에서 다시 튼 곡(`RECENT`)까지 `RECOMMEND` 로
 * 세면, 추천이 한 일과 사용자가 이미 알던 곡을 되꺼낸 일이 한 통에 섞여 추천 성과가
 * 부풀려진다. 그 둘은 어느 화면에서 눌러도 뜻이 같아 화면으로 갈음할 수 없다.
 *
 * `ARTIST` 는 아티스트 정보 패널에서 그 아티스트의 다른 곡을 찾아 튼 재생이다. 패널이
 * 모든 곡 줄에서 열려 화면으로 갈음하면 같은 행동이 세 값으로 흩어졌다. 패널은 안에서
 * 검색 API 를 쓰지만 **사용자가 검색한 것이 아니라** 아티스트를 누른 것이므로 `SEARCH`
 * 도 아니다.
 *
 * `AUTOPLAY` 는 두지 않는다. 검색 결과를 이어 듣는 것은 `SEARCH` 로 남기고, 큐가
 * 알아서 넘어간 재생은 `QUEUE` 다 — 그 순서를 만든 것도 사용자이고, 추천이 한 선택이
 * 아니다.
 *
 * **재생 목록이 끝나 추천이 이어 붙인 곡은 `RECOMMEND` 다** (S15P21A204-439). 사용자가
 * 누르지는 않았지만 곡을 고른 것은 추천이고, 값을 따로 파면 집계하는 자리마다 두 값을
 * 모두 적어 줘야 해서 한 곳만 빠뜨려도 같은 재생이 AI 였다가 아니었다가 한다. 그 자리는
 * `entry_path = 'RECOMMEND'` 를 문자열로 박아 쓰는 곳이 코드·SQL·추천기에 흩어져 있다.
 */
export type EntryPath =
  | 'RECOMMEND'
  | 'SEARCH'
  | 'PLAYLIST'
  | 'QUEUE'
  | 'RECENT'
  | 'ARTIST';
export type SkipTrigger = 'NEXT_BUTTON' | 'OTHER_TRACK';
export type FeedbackTarget = 'TRACK' | 'SECTION';
export type RecType = 'HOME' | 'SECTION' | 'CUSTOM';

/** 재생 하나를 시작할 때 주는 맥락. `play_id` 는 SDK 가 발급한다. */
export type PlayContext = {
  trackId: number;
  entryPath: EntryPath;
  /** 추천에서 들어왔을 때만. 추천 응답이 준 값을 그대로 되돌려준다. */
  rec?: { id: string; type: RecType; rank: number };
};

export type EventParams = {
  position_ms?: number;
  /** SEEK_* 는 seek 직전 위치, PLAY_PROGRESS 는 직전 하트비트의 위치. */
  from_ms?: number;
  start_ms?: number;
  end_ms?: number;
  target?: FeedbackTarget;
  skip_trigger?: SkipTrigger;
  reason?: string;
  playlist_id?: number;
  comment_id?: number;
  content?: string;
  search_query?: string;
  ext?: Record<string, string>;
};

export type ListenEvent = EventParams & {
  event_id: string;
  event_type: EventType;
  /** 발생 시각. 전송 시각이 아니다. ISO-8601(UTC) 로 보내고 ClickHouse 가 Asia/Seoul 로 옮긴다. */
  occurred_at: string;
  track_id?: number;
  play_id?: string;
  entry_path?: EntryPath;
  rec_id?: string;
  rec_type?: RecType;
  rec_rank?: number;
};
