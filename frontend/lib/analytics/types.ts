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

/** `AUTOPLAY` 는 없다. 인플레이어 다음곡 추천이 없어서, 검색 결과를 이어 듣는 것도 SEARCH 로 남긴다. */
export type EntryPath = 'RECOMMEND' | 'SEARCH' | 'PLAYLIST';
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
