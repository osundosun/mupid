'use client';
import { useEffect, useMemo, useRef, useState } from 'react';
import { CalendarDays, ChevronLeft, ChevronRight } from 'lucide-react';
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api/session';
import type { DailyReport } from '@/lib/api/endpoints';
import { toImageSource } from '@/lib/platform/image';
import { MupidMascot } from './mascot';

/**
 * 이번 주 MUBTI — 리포트의 MUBTI 카드에서 '이번 주 보기' 를 누르면 뜨는 대화상자.
 *
 * 일곱 날을 카드 한 장씩 가로로 넘겨 본다. 카드마다 요일·날짜, 그날의 유형
 * 캐릭터를 크게, 아래에 유형 이름. 들었지만 표본이 모자라 유형이 없는 날과
 * 안 들은 날은 그 말을 적는다. 달력을 리포트 본문에 그대로 펼치면 자리를 너무
 * 먹어, 버튼 하나만 남기고 안은 대화상자로 옮겼다.
 *
 * 서버의 `/api/v1/report?date=` 는 지난 날짜를 스냅샷(`STORED`)으로 준다 — 없던
 * 날짜는 그때 계산해 저장하므로 두 번째부터는 가볍다. 대화상자를 열면 그 주의
 * 지난 날짜를 셋씩 받고, 받은 것은 모듈에 남겨 다시 열어도 다시 받지 않는다.
 * 오늘치는 리포트 화면의 폴링이 들고 있으므로 그대로 쓴다.
 */

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];
const CONCURRENCY = 3;
const DAY_MS = 86_400_000;

type Day = { code: string | null; title: string | null; image: string | null; listened: boolean };
const cache = new Map<string, Day>();
const inflight = new Map<string, Promise<void>>();

const pad = (n: number) => String(n).padStart(2, '0');
const keyOf = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
const parse = (k: string) => new Date(Number(k.slice(0, 4)), Number(k.slice(5, 7)) - 1, Number(k.slice(8, 10)));
const summarize = (r: DailyReport): Day => ({
  code: r.mubti?.code ?? null, title: r.mubti?.title ?? null, image: r.mubti?.image_url ?? null, listened: r.total_listening_ms > 0,
});

function fetchDay(date: string): Promise<void> {
  if (cache.has(date)) return Promise.resolve();
  const running = inflight.get(date);
  if (running) return running;
  const p = api.report.daily(date)
    .then(report => { cache.set(date, summarize(report)); })
    .catch(() => { /* 실패한 날은 비워 두고, 다음에 다시 열면 또 시도한다. */ })
    .then(() => { inflight.delete(date); });
  inflight.set(date, p);
  return p;
}

/** 리포트의 MUBTI 카드에 두는 버튼. 누르면 아래 대화상자가 열린다. */
export function WeeklyMubtiButton({ today }: { today: DailyReport | null }) {
  const [open, setOpen] = useState(false);
  return <>
    <button type="button" className="weekly-mubti-button" aria-haspopup="dialog" aria-expanded={open} onClick={() => setOpen(true)}>
      <CalendarDays aria-hidden="true" />이번 주 보기
    </button>
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent className="sky-dialog weekly-mubti-dialog">
        {open && <WeeklyMubti today={today} />}
      </DialogContent>
    </Dialog>
  </>;
}

function WeeklyMubti({ today }: { today: DailyReport | null }) {
  const todayKey = useMemo(() => today?.date ?? keyOf(new Date()), [today?.date]);
  const thisSunday = useMemo(() => { const d = parse(todayKey); d.setDate(d.getDate() - d.getDay()); return d; }, [todayKey]);
  const [offset, setOffset] = useState(0); // 0 = 이번 주, -1 = 지난 주 …
  const sunday = new Date(thisSunday.getTime() + offset * 7 * DAY_MS);
  const days = Array.from({ length: 7 }, (_, i) => new Date(sunday.getTime() + i * DAY_MS));
  const [, bump] = useState(0);
  const rail = useRef<HTMLDivElement>(null);

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

  // 이번 주를 열면 오늘 카드가 보이게, 지난 주는 첫 카드부터.
  useEffect(() => {
    const box = rail.current;
    if (!box) return;
    const target = offset === 0 ? box.querySelector<HTMLElement>('.weekly-card.is-today') : box.firstElementChild as HTMLElement | null;
    if (target) box.scrollTo({ left: target.offsetLeft - box.offsetLeft - 16, behavior: 'auto' });
  }, [offset]);

  const dayOf = (k: string): Day | undefined => k === todayKey ? (today ? summarize(today) : undefined) : cache.get(k);
  const first = days[0], last = days[6];
  const range = first.getMonth() === last.getMonth()
    ? `${first.getMonth() + 1}월 ${first.getDate()}일 – ${last.getDate()}일`
    : `${first.getMonth() + 1}월 ${first.getDate()}일 – ${last.getMonth() + 1}월 ${last.getDate()}일`;
  const slide = (dir: -1 | 1) => {
    const box = rail.current; if (!box) return;
    const card = box.querySelector<HTMLElement>('.weekly-card');
    box.scrollBy({ left: dir * ((card?.offsetWidth ?? 200) + 12), behavior: 'smooth' });
  };

  return <>
    <DialogHeader>
      <DialogTitle>{offset === 0 ? '이번 주 MUBTI' : `${range} MUBTI`}</DialogTitle>
      <DialogDescription>날짜별로 넘기며 그날의 유형을 확인해 보세요.</DialogDescription>
    </DialogHeader>
    <div className="weekly-nav">
      <button type="button" className="icon-button" aria-label="지난 주" onClick={() => setOffset(v => v - 1)}><ChevronLeft /></button>
      <strong aria-live="polite">{range}</strong>
      <button type="button" className="icon-button" aria-label="다음 주" disabled={offset >= 0} onClick={() => setOffset(v => v + 1)}><ChevronRight /></button>
    </div>
    <div className="weekly-rail-wrap">
      <button type="button" className="weekly-arrow is-prev" aria-label="이전 날짜" onClick={() => slide(-1)}><ChevronLeft /></button>
      <div ref={rail} className="weekly-rail" role="list" aria-label={`${range} 날짜별 MUBTI`}>
        {days.map((d, i) => {
          const k = keyOf(d);
          const future = k > todayKey;
          const day = future ? undefined : dayOf(k);
          const src = day?.image ? toImageSource(day.image) : '';
          const state = future ? 'future' : day?.code ? 'typed' : day?.listened ? 'listened' : day ? 'empty' : 'loading';
          return <article key={k} role="listitem" className={`weekly-card is-${state}${k === todayKey ? ' is-today' : ''}`}>
            <header>
              <span className={`weekly-weekday${i === 0 ? ' is-sun' : i === 6 ? ' is-sat' : ''}`}>{WEEKDAYS[i]}요일</span>
              <strong>{d.getMonth() + 1}.{pad(d.getDate())}</strong>
              {k === todayKey && <em>오늘</em>}
            </header>
            <div className="weekly-art">
              {state === 'typed'
                // 앱(Tauri)에서는 서버 그림 주소를 `toImageSource` 로 바꿔야 열린다 — `ReportMubtiArt` 와 같은 사정.
                ? (src ? <img src={src} alt={day!.code!} width={120} height={120} loading="lazy" decoding="async" /> : <span className="weekly-code">{day!.code}</span>)
                : <MupidMascot size={84} className="weekly-mascot" />}
            </div>
            <footer>
              {state === 'typed' && <><strong>{day!.code}</strong>{day!.title && <span>{day!.title}</span>}</>}
              {state === 'listened' && <span>들었지만 유형이 열리지 않았어요</span>}
              {state === 'empty' && <span>들은 음악이 없어요</span>}
              {state === 'future' && <span>아직 오지 않은 날</span>}
              {state === 'loading' && <span>{k === todayKey ? '오늘의 리포트를 기다리고 있어요' : '불러오고 있어요…'}</span>}
            </footer>
          </article>;
        })}
      </div>
      <button type="button" className="weekly-arrow is-next" aria-label="다음 날짜" onClick={() => slide(1)}><ChevronRight /></button>
    </div>
  </>;
}
