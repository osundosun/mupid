/**
 * 사용자 여정 데이터 수집 라이브러리.
 *
 * 이벤트 22종을 큐에 모아 배치로 `POST /api/v1/events` 에 보낸다. 설계 배경은
 * `api/knowledge/decision/0014-event-collector-in-api.md`, 이벤트 계약은
 * `api/knowledge/domain/listening-events.md` 에 있다.
 *
 * 원칙 세 가지만 기억하면 된다.
 *
 *   1. `position_ms` 는 **매번 `audio.currentTime` 을 실제로 읽어서** 만든다.
 *   2. 구간 번호를 보내지 않는다. 밀리초만 보내고 구간 매핑은 백엔드가 한다.
 *   3. 가중치·분류를 계산하지 않는다. 원시 신호만 남기고 해석은 분석 시점에 한다.
 *
 * 붙이는 방법:
 *
 * ```ts
 * useEffect(() => bindAudio(audioRef.current!, analytics), []);
 *
 * // 재생을 시작할 때 — 진입 경로는 이 재생이 어디서 왔는지다.
 * analytics.startPlay({ trackId: track.id, entryPath: 'SEARCH' });
 *
 * // 추천에서 들어왔다면 추천 응답이 준 값을 그대로 되돌려준다.
 * analytics.startPlay({ trackId, entryPath: 'RECOMMEND', rec: { id, type: 'SECTION', rank: 2 } });
 *
 * // 슬라이더를 놓았을 때 (프로그램이 거는 seek 에서는 부르지 않는다)
 * analytics.seek(beforeMs, afterMs);
 *
 * // 다음 곡 버튼으로 넘겼을 때
 * analytics.skip(positionMs, 'NEXT_BUTTON');
 * ```
 */
export type {
  EntryPath,
  EventParams,
  EventType,
  FeedbackTarget,
  ListenEvent,
  PlayContext,
  RecType,
  SkipTrigger,
} from './types';
export {
  consoleTransport,
  createTracker,
  HEARTBEAT_MS,
  type Tracker,
  type TrackerOptions,
} from './tracker';
export { bindAudio } from './audio';
export {
  createHttpTransport,
  createNativeTransport,
  type HttpTransportOptions,
  type Transport,
} from './transport';

import { apiClient } from '../api/session';
import { isTauri } from '../platform';
import { createTracker } from './tracker';
import { createHttpTransport, createNativeTransport } from './transport';

/**
 * 앱 전역 인스턴스.
 *
 * 액세스 토큰은 `ApiClient` 가 메모리에 들고 있다. SDK 는 보관 위치를 모른 채
 * 매 배치마다 값만 받아 간다.
 *
 * 이 전송은 `ApiClient` 를 거치지 않는다. 페이지를 떠나는 중에도 나가야 해서
 * `fetch(keepalive)` 가 필요한데, 그건 이 전송만의 사정이다. 대신 401 을 만나면
 * 토큰을 새로 받지 못하고 큐에 되돌려 두었다가 다음 배치에서 재시도한다 —
 * 그 사이 다른 요청이 토큰을 갱신해 둔다.
 *
 * 비로그인이면 `Authorization` 이 비어 나가고 서버가 401 을 준다. 이벤트는
 * 큐에 남았다가 로그인 뒤 함께 나간다.
 *
 * 앱에서는 같은 일을 Rust 가 한다 — 웹뷰는 인터넷에 직접 나가지 않는다.
 */
const authorization = () => {
  const token = apiClient.getAccessToken();
  return token ? `Bearer ${token}` : undefined;
};

export const analytics = createTracker({
  transport: isTauri()
    ? createNativeTransport({ authorization })
    : createHttpTransport({ authorization }),
});
