/**
 * 취향 리포트 조회.
 *
 * <h2>왜 폴링인가</h2>
 *
 * 방금 들은 곡이 리포트에 반영되기까지 **평균 20초, 최악 45초** 걸린다.
 *
 *   재생 → 다음 하트비트        0~5초   (`lib/analytics/player.ts` HEARTBEAT_MS)
 *   큐 → 서버 전송              0~5초   (`lib/analytics/tracker.ts` FLUSH_MS)
 *   Kafka → ClickHouse          0~15초  (서버 `kafka_flush_interval_ms`)
 *
 * 마지막 15초는 작은 파트가 쌓여 병합 압력이 오르는 것을 막으려고 서버가 일부러
 * 올려 둔 값이라 이 화면 때문에 낮추지 않는다. 원천이 20초 지연인데 SSE·WebSocket
 * 으로 전송만 실시간으로 만들면 비용만 늘고 체감은 그대로다. 그래서 **폴링으로
 * 지연을 덮는다.**
 *
 * 리포트 화면에 있는 동안만, 탭이 보일 때만 돈다. 가려진 탭에서 30초마다 서버를
 * 때릴 이유가 없다.
 */

import { useEffect, useRef, useState } from 'react';
import { api } from './api/session';
import type { DailyReport } from './api/endpoints';

/** 서버 집계 지연(≈20초)보다 조금 길게. 더 당겨도 새 값이 나오지 않는다. */
const POLL_MS = 30000;

export type ReportState = {
  report: DailyReport | null;
  /** 첫 로딩. 폴링으로 다시 받는 동안은 이전 값을 그대로 보여 준다. */
  loading: boolean;
  /** 사용자에게 보여 줄 오류 문구. 없으면 빈 문자열. */
  error: string;
};

/**
 * 오늘치 리포트를 구독한다.
 *
 * @param active 이 화면이 보이는 동안만 true. false 면 폴링을 멈춘다.
 */
export function useDailyReport(active: boolean): ReportState {
  const [state, setState] = useState<ReportState>({
    report: null,
    loading: true,
    error: '',
  });
  // 폴링으로 다시 받을 때 화면을 비우지 않으려고 직전 값을 들고 있는다.
  const latest = useRef<DailyReport | null>(null);

  useEffect(() => {
    if (!active) return;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout> | null = null;
    let stopped = false;

    const load = async () => {
      try {
        const value = await api.report.daily(undefined, abort.signal);
        if (stopped) return;
        latest.current = value;
        setState({ report: value, loading: false, error: '' });
      } catch (error) {
        if (stopped || abort.signal.aborted) return;
        // 이미 받아 둔 리포트가 있으면 지우지 않는다. 한 번 실패했다고 화면이
        // 비면, 잠깐 끊긴 것과 데이터가 없는 것을 구분할 수 없다.
        setState({
          report: latest.current,
          loading: false,
          error:
            latest.current === null
              ? '리포트를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.'
              : '',
        });
      } finally {
        if (!stopped) schedule();
      }
    };

    const schedule = () => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        // 가려진 탭에서는 건너뛰고 다시 예약만 한다. 돌아오면 아래 리스너가
        // 기다리지 않고 바로 받아 온다.
        if (document.visibilityState === 'visible') void load();
        else schedule();
      }, POLL_MS);
    };

    const onVisible = () => {
      if (document.visibilityState === 'visible') void load();
    };

    void load();
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      document.removeEventListener('visibilitychange', onVisible);
      abort.abort();
    };
  }, [active]);

  return state;
}

/** `1시간 24분` / `12분 30초` / `41초`. 0 이면 빈 문자열. */
export function formatListening(ms: number): string {
  const total = Math.round(ms / 1000);
  if (total <= 0) return '';
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours > 0) return `${hours}시간 ${minutes}분`;
  if (minutes > 0) return `${minutes}분 ${seconds}초`;
  return `${seconds}초`;
}
