'use client';
import { useEffect, useMemo, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { api } from '@/lib/api/session';
import type { DailyReport } from '@/lib/api/endpoints';
import { toImageSource } from '@/lib/platform/image';

/**
 * 이번 주 일곱 날에 어떤 MUBTI 가 찍혔는지 훑는 주간 달력.
 *
 * 한 달을 다 그리면 칸이 작아 캐릭터가 보이지 않았다. 한 주만 그려 칸을 키우고,
 * 칸마다 그날의 MUBTI 캐릭터와 네 글자를 놓는다. 들었지만 표본이 모자라 유형이
 * 없는 날은 점 하나, 안 들은 날은 비운다. 화살표로 지난 주를 넘겨 본다.
 *
 * 서버의 `/api/v1/report?date=` 는 지난 날짜를 스냅샷(`STORED`)으로 준다 — 없던
 * 날짜는 그때 계산해 저장하므로 두 번째부터는 가볍다. 한 주의 지난 날짜를 셋씩
 * 받고, 받은 것은 모듈에 남겨 화면을 오가도 다시 받지 않는다. 오늘치는 위의
 * 폴링이 들고 있으므로 여기서는 받지 않고 그대로 쓴다.
 */

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
/** 한 번에 띄우는 요청 수. 스냅샷이 없던 주를 처음 열 때 서버를 한꺼번에 때리지 않는다. */
const CONCURRENCY = 3;
const DAY_MS = 86_400_000;

type Day = { code: string | null; image: string | null; listened: boolean };
const cache = new Map<string, Day>();
const inflight = new Map<string, Promise<void>>();

const pad = (n: number) => String(n).padStart(2, '0');
const keyOf = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = (k: string) => new Date(Number(k.slice(0, 4)), Number(k.slice(5, 7)) - 1, Number(k.slice(8, 10)));
const summarize = (r: DailyReport): Day => ({ code: r.mubti?.code ?? null, image: r.mubti?.image_url ?? null, listened: r.total_listening_ms > 0 });

function fetchDay(date: string): Promise<void> {
  if (cache.has(date)) return Promise.resolve();
  const running = inflight.get(date);
  if (running) return running;
  const p = api.report.daily(date)
    .then(report => { cache.set(date, summarize(report)); })
    .catch(() => { /* 실패한 날은 비워 두고, 다음에 주를 다시 열면 또 시도한다. */ })
    .then(() => { inflight.delete(date); });
  inflight.set(date, p);
  return p;
}

export function ListeningCalendar({ today }: { today: DailyReport | null }) {
  // 서버 기준(Asia/Seoul) 오늘. 오늘치 리포트가 오기 전에는 기기 시간으로 시작한다.
  const todayKey = useMemo(() => today?.date ?? keyOf(new Date()), [today?.date]);
  // 보고 있는 주의 일요일.
  const thisSunday = useMemo(() => { const d = parse(todayKey); d.setDate(d.getDate() - d.getDay()); return d; }, [todayKey]);
  const [offset, setOffset] = useState(0); // 0 = 이번 주, -1 = 지난 주 …
  const sunday = new Date(thisSunday.getTime() + offset * 7 * DAY_MS);
  const days = Array.from({ length: 7 }, (_, i) => new Date(sunday.getTime() + i * DAY_MS));
  const [, bump] = useState(0);

  // 이 주의 지난 날짜를 셋씩 받는다.
  useEffect(() => {
    let stopped = false;
    const todo = days.map(keyOf).filter(k => k < todayKey && !cache.has(k));
    const run = async () => {
      while (todo.length && !stopped) {
        await Promise.all(todo.splice(0, CONCURRENCY).map(fetchDay));
        if (!stopped) bump(v => v + 1);
      }
    };
    void run();
    return () => { stopped = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- days 는 offset·todayKey 로만 정해진다
  }, [offset, todayKey]);

  const dayOf = (k: string): Day | undefined => k === todayKey ? (today ? summarize(today) : undefined) : cache.get(k);
  const first = days[0], last = days[6];
  const range = first.getMonth() === last.getMonth()
    ? `${first.getMonth() + 1}월 ${first.getDate()}일 – ${last.getDate()}일`
    : `${first.getMonth() + 1}월 ${first.getDate()}일 – ${last.getMonth() + 1}월 ${last.getDate()}일`;

  return <section className="report-card listening-calendar" aria-labelledby="listening-calendar-heading">
    <div className="calendar-head">
      <h2 id="listening-calendar-heading">이번 주 MUBTI</h2>
      <div className="calendar-month">
        <button type="button" className="icon-button" aria-label="지난 주" onClick={() => setOffset(v => v - 1)}><ChevronLeft /></button>
        <strong aria-live="polite">{offset === 0 ? '이번 주' : range}</strong>
        <button type="button" className="icon-button" aria-label="다음 주" disabled={offset >= 0} onClick={() => setOffset(v => v + 1)}><ChevronRight /></button>
      </div>
    </div>
    <p className="calendar-caption">들은 날마다 그날의 유형이 도장처럼 찍혀요.</p>
    <div className="calendar-week" role="list" aria-label={`${range} MUBTI`}>
      {days.map((d, i) => {
        const k = keyOf(d);
        const future = k > todayKey;
        const day = future ? undefined : dayOf(k);
        const state = day?.code ? 'typed' : day?.listened ? 'listened' : 'empty';
        const spoken = `${d.getMonth() + 1}월 ${d.getDate()}일 ${WEEKDAYS[i]}요일${k === todayKey ? ' 오늘' : ''}${day?.code ? `, ${day.code}` : day?.listened ? ', 유형 없음' : ''}`;
        const src = day?.image ? toImageSource(day.image) : '';
        return <div key={k} role="listitem" className={`calendar-day is-${state}${k === todayKey ? ' is-today' : ''}${future ? ' is-future' : ''}`} aria-label={spoken}>
          <span className={`calendar-weekday${i === 0 ? ' is-sun' : i === 6 ? ' is-sat' : ''}`} aria-hidden="true">{WEEKDAYS[i]}</span>
          <span className="calendar-date" aria-hidden="true">{d.getDate()}</span>
          <span className="calendar-stamp" aria-hidden="true">
            {day?.code
              // 앱(Tauri)에서는 서버 그림 주소를 `toImageSource` 로 바꿔야 열린다 — `ReportMubtiArt` 와 같은 사정.
              ? (src ? <img className="calendar-art" src={src} alt="" width={56} height={56} loading="lazy" decoding="async" /> : <span className="calendar-code">{day.code}</span>)
              : day?.listened ? <span className="calendar-dot" /> : null}
          </span>
          <span className="calendar-label" aria-hidden="true">{day?.code ?? (future ? '' : '—')}</span>
        </div>;
      })}
    </div>
  </section>;
}
