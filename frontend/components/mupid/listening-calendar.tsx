'use client';
import { useEffect, useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { api } from '@/lib/api/session';
import type { DailyReport } from '@/lib/api/endpoints';
import { toImageSource } from '@/lib/platform/image';

/**
 * 날마다 어떤 MUBTI 가 찍혔는지 한 달 단위로 훑는 작은 달력.
 *
 * 칸에는 날짜와 그날의 MUBTI 캐릭터 그림을 놓는다(그림이 없는 유형은 네 글자).
 * 들었지만 표본이 모자라 유형이 없는 날은 점 하나, 아무것도 안 들은 날은 숫자만.
 * 상세는 펼치지 않는다 —
 * 리포트 화면에서 달력이 차지하는 자리를 최소로 두려는 것이다.
 *
 * 서버의 `/api/v1/report?date=` 는 지난 날짜를 스냅샷(`STORED`)으로 준다 — 없던
 * 날짜는 그때 계산해 저장하므로 두 번째부터는 가볍다. 달을 펼치면 그 달의 지난
 * 날짜를 셋씩 받고, 받은 것은 모듈에 남겨 화면을 오가도 다시 받지 않는다.
 * 오늘치는 위의 폴링이 들고 있으므로 여기서는 받지 않고 그대로 쓴다.
 */

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
/** 한 번에 띄우는 요청 수. 스냅샷이 없던 달을 처음 열 때 서버를 한꺼번에 때리지 않는다. */
const CONCURRENCY = 3;

type Day = { code: string | null; image: string | null; listened: boolean };
const cache = new Map<string, Day>();
const inflight = new Map<string, Promise<void>>();

const pad = (n: number) => String(n).padStart(2, '0');
const key = (y: number, m: number, d: number) => `${y}-${pad(m + 1)}-${pad(d)}`;
const summarize = (r: DailyReport): Day => ({ code: r.mubti?.code ?? null, image: r.mubti?.image_url ?? null, listened: r.total_listening_ms > 0 });

function fetchDay(date: string): Promise<void> {
  if (cache.has(date)) return Promise.resolve();
  const running = inflight.get(date);
  if (running) return running;
  const p = api.report.daily(date)
    .then(report => { cache.set(date, summarize(report)); })
    .catch(() => { /* 실패한 날은 비워 두고, 다음에 달을 다시 열면 또 시도한다. */ })
    .then(() => { inflight.delete(date); });
  inflight.set(date, p);
  return p;
}

export function ListeningCalendar({ today }: { today: DailyReport | null }) {
  // 서버 기준(Asia/Seoul) 오늘. 오늘치 리포트가 오기 전에는 기기 시간으로 시작한다.
  const todayKey = useMemo(() => {
    if (today?.date) return today.date;
    const now = new Date();
    return key(now.getFullYear(), now.getMonth(), now.getDate());
  }, [today?.date]);
  const [ty, tm] = [Number(todayKey.slice(0, 4)), Number(todayKey.slice(5, 7)) - 1];
  const [month, setMonth] = useState({ y: ty, m: tm });
  const [, bump] = useState(0);

  // 이 달의 지난 날짜를 셋씩 받는다. 달을 넘기면 그 달을 받는다.
  useEffect(() => {
    let stopped = false;
    const last = new Date(month.y, month.m + 1, 0).getDate();
    const days: string[] = [];
    for (let d = 1; d <= last; d++) {
      const k = key(month.y, month.m, d);
      if (k < todayKey && !cache.has(k)) days.push(k);
    }
    const run = async () => {
      while (days.length && !stopped) {
        await Promise.all(days.splice(0, CONCURRENCY).map(fetchDay));
        if (!stopped) bump(v => v + 1);
      }
    };
    void run();
    return () => { stopped = true; };
  }, [month.y, month.m, todayKey]);

  const first = new Date(month.y, month.m, 1).getDay();
  const last = new Date(month.y, month.m + 1, 0).getDate();
  const cells: (number | null)[] = [...Array<null>(first).fill(null), ...Array.from({ length: last }, (_, i) => i + 1)];
  while (cells.length % 7) cells.push(null);
  const atCurrentMonth = month.y === ty && month.m === tm;
  const dayOf = (k: string): Day | undefined => k === todayKey ? (today ? summarize(today) : undefined) : cache.get(k);

  return <section className="report-card listening-calendar" aria-labelledby="listening-calendar-heading">
    <div className="calendar-head">
      <h2 id="listening-calendar-heading">MUBTI 달력</h2>
      <div className="calendar-month">
        <button type="button" className="icon-button" aria-label="이전 달" onClick={() => setMonth(({ y, m }) => m === 0 ? { y: y - 1, m: 11 } : { y, m: m - 1 })}><ChevronLeft /></button>
        <strong aria-live="polite">{month.y}.{pad(month.m + 1)}</strong>
        <button type="button" className="icon-button" aria-label="다음 달" disabled={atCurrentMonth} onClick={() => setMonth(({ y, m }) => m === 11 ? { y: y + 1, m: 0 } : { y, m: m + 1 })}><ChevronRight /></button>
      </div>
    </div>
    <p className="calendar-caption">들은 날마다 그날의 유형이 도장처럼 찍혀요.</p>
    <div className="calendar-grid" role="grid" aria-label={`${month.y}년 ${month.m + 1}월 MUBTI`}>
      {WEEKDAYS.map(w => <span key={w} className="calendar-weekday" role="columnheader">{w}</span>)}
      {cells.map((d, i) => {
        if (d === null) return <span key={`e${i}`} aria-hidden="true" />;
        const k = key(month.y, month.m, d);
        const future = k > todayKey;
        const day = future ? undefined : dayOf(k);
        const state = day?.code ? 'typed' : day?.listened ? 'listened' : 'empty';
        const spoken = `${month.m + 1}월 ${d}일${k === todayKey ? ' 오늘' : ''}${day?.code ? `, ${day.code}` : day?.listened ? ', 유형 없음' : ''}`;
        return <span
          key={k}
          role="gridcell"
          className={`calendar-day is-${state}${k === todayKey ? ' is-today' : ''}${future ? ' is-future' : ''}`}
          aria-label={spoken}
        >
          <span className="calendar-date">{d}</span>
          {day?.code
            ? <span className="calendar-stamp" title={day.code}>
              {toImageSource(day.image)
                // 앱(Tauri)에서는 서버 그림 주소를 `toImageSource` 로 바꿔야 열린다 — 위 `ReportMubtiArt` 와 같은 사정.
                ? <img className="calendar-art" src={toImageSource(day.image)} alt="" width={40} height={40} loading="lazy" decoding="async" />
                : <span className="calendar-code" aria-hidden="true">{day.code}</span>}
            </span>
            : day?.listened ? <span className="calendar-dot" aria-hidden="true" /> : null}
        </span>;
      })}
    </div>
  </section>;
}
