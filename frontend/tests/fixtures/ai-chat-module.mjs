/**
 * `lib/ai-chat.ts` 를 **실제 코드 그대로** 세운다 (S15P21A204-424).
 *
 * ## 왜 스텁을 늘리지 않고 진짜를 세우는가
 *
 * 챗봇이 카탈로그 의존을 버리면서 `ai-chat.ts` 가 `stream-catalog.toTrack` 과
 * `recommendations.sectionName` 을 쓰게 됐다. **그 둘이 이 수정의 핵심이다** — 챗봇이
 * 목록·이어듣기와 같은 변환을 쓰는지가 관심사인데, 가짜 `toTrack` 을 물리면 시험이
 * 그 질문에 답하지 못하고 "내가 쓴 가짜가 잘 도는가" 만 확인하게 된다.
 *
 * 그래서 **경계만** 바꾼다: 서버 호출(`api/session`)·오류 형(`api/client`)·기기
 * 기능(`platform`·`api/local-demo`). 곡 변환은 저장소의 진짜 코드가 돈다.
 *
 * ## 세우는 차례
 *
 * 모듈을 data: URL 로 만들어 상대 경로 import 를 **이미 세운 URL 로 바꿔 끼운다.**
 * data: URL 안에서는 `./recommendations` 같은 상대 경로가 해석되지 않으므로
 * (`ERR_UNSUPPORTED_RESOLVE_REQUEST`), 의존성이 없는 것부터 차례로 세워야 한다:
 *
 *     mupid-data → stream-catalog → recommendations → ai-chat
 */

import { readFile } from 'node:fs/promises';
import ts from 'typescript';

export const inline = (source) =>
  'data:text/javascript;base64,' + Buffer.from(source).toString('base64');

const read = (rel) => readFile(new URL('../../' + rel, import.meta.url), 'utf8');

const compile = (source) =>
  ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  }).outputText;

/** 상대 경로 import 를 이미 세운 모듈 URL 로 바꿔 끼운다. */
const wire = (js, links) =>
  Object.entries(links).reduce(
    (out, [specifier, url]) => out.replaceAll(`'${specifier}'`, JSON.stringify(url)),
    js,
  );

// ── 경계 대역 ────────────────────────────────────────────────
// 대화 한 번을 `globalThis.__aiCalls` 에 적고 `globalThis.__aiReply` 를 돌려준다.
export const sessionStub = inline(`
  export const api = {
    ai: {
      chat: async (body) => {
        globalThis.__aiCalls.push(body);
        return globalThis.__aiReply;
      },
    },
  };
`);

export const clientStub = inline(`
  export class ApiError extends Error {
    constructor(status, code, message) { super(message); this.status = status; this.code = code; }
  }
`);

// 앱(Tauri)에서만 쓰는 기능이다. 브라우저 경로로 고정한다.
const localDemoStub = inline(`export const localDemoEnabled = () => false;`);
const platformStub = inline(`
  export const toImageSource = (url) => url ?? '';
`);

// ── 진짜 모듈들 ──────────────────────────────────────────────
const mupidData = inline(compile(await read('lib/mupid-data.ts')));

const streamCatalog = inline(
  wire(compile(await read('lib/stream-catalog.ts')), {
    './api/session': sessionStub,
    './api/local-demo': localDemoStub,
    './mupid-data': mupidData,
    './platform': platformStub,
  }),
);

const recommendations = inline(
  wire(compile(await read('lib/recommendations.ts')), {
    './api/session': sessionStub,
    './mupid-data': mupidData,
    './stream-catalog': streamCatalog,
  }),
);

export const aiChat = await import(
  inline(
    wire(compile(await read('lib/ai-chat.ts')), {
      './api/session': sessionStub,
      './api/client': clientStub,
      './mupid-data': mupidData,
      './recommendations': recommendations,
      './stream-catalog': streamCatalog,
    }),
  )
);

export const { ApiError } = await import(clientStub);
export const { toTrack } = await import(streamCatalog);

/**
 * 서버가 내려 주는 추천 곡 하나. **`music` 을 통째로 싣는 지금 계약 그대로**다.
 *
 * `playable` 을 끄면 "아직 재생할 수 없는 곡" 이 된다 — 화면이 줄을 그리지 않고
 * 몇 곡이 그렇게 빠졌는지만 알리는 경우다.
 */
export const recommendedTrack = ({
  musicId = 2,
  title = '추천 곡',
  playable = true,
  durationMs = 120_000,
  rank = 1,
  startMs = 30_000,
  endMs = 45_000,
  score = 0.9,
  reason = '이 구간이 닮았어요',
  sectionLabel = 'CHORUS',
} = {}) => ({
  rank_order: rank,
  music: {
    music_id: musicId,
    title,
    release_date: '2026-01-01T00:00:00',
    duration_ms: durationMs,
    playable,
    play_count: 0,
    like_count: 0,
    image_url: `/cover-${musicId}.webp`,
    artist: { artist_id: 1, name: '테스트 아티스트', image_url: null },
    album: { album_id: 1, title: '테스트 앨범', image_url: null, release_date: '2026-01-01T00:00:00' },
    genres: [],
    moods: [],
  },
  music_section_id: musicId * 10,
  section_label: sectionLabel,
  start_time_ms: startMs,
  end_time_ms: endMs,
  score,
  reason_code: 'QUERY_FILTER_MATCH',
  reason,
});
