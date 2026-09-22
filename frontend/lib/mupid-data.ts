// 타입만 가져온다 — 값을 import 하면 화면 데이터 모듈이 수집 SDK 에 묶인다.
import type { RecType } from './analytics/types';

export type SongSection = {
  label: string;
  start: number;
  end: number;
  /**
   * `music_section_analysis_id`. **서버가 분석한 구간에만 있다** —
   * 길이만 보고 둘로 나눈 임시 구간(`lib/stream-catalog.ts`)에는 없다.
   *
   * 「닮은 구간」을 물으려면 이 번호가 필요하다. 없으면 물을 수 없고, 화면은
   * 그때 곡 목록으로 물러난다.
   */
  sectionId?: number;
};

/**
 * 추천으로 온 곡에만 붙는다. 서버가 **어느 구간을 왜 골랐는지**가 여기 들어간다.
 *
 * `Track` 본체에 섞지 않는 이유는 카탈로그에서 온 곡과 추천에서 온 곡을 구분해야
 * 하기 때문이다 — 추천이 아닌 곡에 `reasonCode` 가 있으면 화면이 없는 근거를 그린다.
 */
export type TrackRecommendation = {
  /** 화면에 보여 줄 구간 이름 (`인트로`·`코러스`…). 서버 enum 을 옮긴 값이다. */
  sectionLabel: string;
  /** 구간 시작·끝 (초). 재생은 여기서 시작해 여기서 멈춘다. */
  start: number;
  end: number;
  /** 왜 골랐는가. 모르는 값이 올 수 있으므로 화면은 문구를 그대로 쓴다. */
  reasonCode: string;
  /** 사람이 읽는 이유. 서버가 만든 문장이다. */
  reason: string;
  /** 이 추천 묶음의 번호와 순위. 재생 이벤트에 되돌려 싣는다(`rec_id`·`rec_rank`). */
  recommendationId?: number;
  rank: number;
};

export type Track = {
  /** 로컬 개발용 MP3. 서버 음악 번호와 분리한다. */
  localAudio?: string;
  /**
   * 가사가 원래 없는 연주곡인가.
   *
   * 가사가 비어 있는 이유는 둘이다 — 원래 없는 곡과, 아직 붙이지 못한 곡.
   * 화면이 둘을 다르게 말해야 해서 데이터가 구분해 준다.
   */
  instrumental?: boolean;
  /** 로컬 메타데이터에서 가져온 시간별 가사. */
  localLyrics?: { lyric_id: number; time_ms: number; text: string }[];
  id: number;
  /**
   * 서버의 음악 번호. 재생은 이 번호로 청크 스트리밍을 받는다.
   * → `lib/stream-catalog.ts` · `lib/player/`
   */
  musicId?: number;
  title: string;
  artist: string;
  artistId?: number;
  artistImage?: string;
  album: string;
  genre: string;
  mood: string;
  moment: number;
  duration: number;
  match: number;
  reason: string;
  image: string;
  tags: string[];
  sections: SongSection[];
  /** 추천으로 온 곡일 때만 채워진다. 카탈로그에서 온 곡에는 없다. */
  recommended?: TrackRecommendation;
  /**
   * 구간 없이 **곡만** 권한 추천의 상관 키. 재생 이벤트의 `rec_id` · `rec_type` ·
   * `rec_rank` 로 되돌려 싣는다.
   *
   * `recommended` 와 나눠 둔 것은 그쪽이 "어느 구간을 왜 골랐는가" 이기 때문이다.
   * 오늘의 Moment 는 구간을 주지 않으므로(`open-questions.md` B7) 거기에 담으면
   * 화면이 **없는 구간을 그린다** — 플레이어의 추천 구간 표시와 구간 이름이 빈 값으로
   * 뜨고, `play(track, true)` 가 `undefined` 지점으로 재생을 건다.
   */
  recSource?: { id: number; type: RecType; rank: number };
  recommendationReason?: string;
};

/**
 * 커버를 구하지 못했을 때 대신 거는 기본 앨범 이미지.
 *
 * 사진이 아니라 정사각 SVG 다. 같은 커버가 목록의 44px 부터 데스크톱의 500px
 * 까지 쓰이는데, 사진을 쓰면 `.album-art img` 의 크롭(`transform: scale(1.8)`)에
 * 걸려 크기마다 다른 자리가 보인다. 그림으로 그려 두면 어느 크기에서나 같고
 * 용량도 1KB 대라 목록을 채워도 부담이 없다.
 */
export const DEFAULT_ALBUM_COVER = '/album-cover-default.svg';

/**
 * 커버 한 장을 어떤 주소로 걸지 정한다.
 *
 * 컴포넌트에서 떼어 둔 것은 판단을 렌더 없이 시험하기 위해서이기도 하지만,
 * 무엇보다 **기본 커버를 걸고 나면 다시 갈아 끼우지 않는다**는 규칙을 한곳에
 * 모아 두기 위해서다. 이 규칙이 없으면 기본 커버까지 못 읽는 상황에서
 * `onError` → 상태 변경 → 다시 그리기가 끝없이 돈다.
 *
 * 비어 있는 값을 런타임에서 받아 내는 이유는 `Track.image` 가 언제나 문자열
 * 이기 때문이다. 여러 화면이 문자열을 전제하고 있어 타입을 optional 로 돌릴 수
 * 없고, 대신 서버가 표지도 앨범 커버도 주지 않아 빈 문자열이 실려 오는 경우
 * (`lib/stream-catalog.ts`)를 여기서 걸러 낸다. 공백만 있는 값도 같이 본다.
 *
 * @param failedSrc 이미 한 번 읽기에 실패한 주소. 그 주소면 곧장 기본 커버다.
 */
export function resolveAlbumCover(
  image: string | null | undefined,
  failedSrc?: string | null,
): { src: string; isFallback: boolean } {
  const wanted = image?.trim() ?? '';
  const isFallback =
    !wanted || wanted === DEFAULT_ALBUM_COVER || wanted === failedSrc;
  return { src: isFallback ? DEFAULT_ALBUM_COVER : wanted, isFallback };
}

export type SavedMoment = {
  id: number;
  track: Track;
  start: number;
  end: number;
  section: string;
};

export type Playlist = {
  id: number;
  name: string;
  tracks: Track[];
};

/**
 * 화면이 그리는 **툴 호출 기록 한 줄** (S15P21A204-409).
 *
 * 서버 응답(`tool_calls`)을 `lib/ai-chat.ts` 가 이 모양으로 옮긴다. 필드 이름을 화면식
 * (`trackCount`)으로 바꾸는 자리가 거기다 — 서버 계약(snake_case)을 컴포넌트까지
 * 끌고 오지 않는다.
 */
export type ChatToolCall = {
  /** 개발자용 식별자. 화면에는 그리지 않는다 — 시험용 훅(`data-tool`)과 로그에만 쓴다. */
  name: string;
  /** 사용자에게 읽히는 이름(`음악 추천`). 서버가 옮겨서 준다. */
  label: string;
  /** 모델이 넘긴 인자. "왜 이 곡이 나왔나" 의 근거다. */
  arguments: Record<string, unknown>;
  succeeded: boolean;
  /** 돌아온 곡 수. 실패했거나 곡이 없으면 0 이다. */
  trackCount: number;
  /** 실패 사유. 성공이면 `null` 이다. */
  failureReason: string | null;
};

export type ChatMessage = {
  id: number;
  role: 'assistant' | 'user';
  text: string;
  tracks?: Track[];
  /**
   * 이 말풍선을 만든 턴에 일어난 툴 호출 기록. **없으면 아무것도 그리지 않는다** —
   * 그냥 대화만 한 턴이다 (S15P21A204-409).
   */
  toolCalls?: ChatToolCall[];
};

/**
 * 화면이 다루는 곡 목록.
 *
 * **데모 곡은 없다.** 서버에 등록된 곡만 보여 준다 — `lib/stream-catalog.ts`
 * 가 `GET /api/v1/music` 로 받아 `MupidApp` 이 이 자리에 넣는다. 여기에
 * 예시 곡을 두면 없는 곡을 재생하려다 실패하고, 좋아요·기록도 서버에 남길 수
 * 없는 번호를 갖게 된다.
 */
export const tracks: Track[] = [];

/**
 * 온보딩 선택지의 **대체 목록**이다.
 *
 * 평소에는 `GET /api/v1/onboarding/option` 이 준 목록을 쓴다 (`lib/onboarding.ts`
 * 의 `loadOnboardingOptions`). 서버가 없거나 비로그인일 때만 여기로 돌아간다 —
 * 온보딩은 앱에 들어가는 관문이라 여기서 막히면 아무것도 못 하기 때문이다.
 *
 * 그래서 **서버 코드표(`codes`)와 순서까지 같게 유지한다.** 서버가 심는 순서는
 * `BootstrapRunner.CODE_SEEDS` 이고, 화면 차례가 곧 `code_id` 차례다
 * (`findAllByCodeTypeOrderByCodeIdAsc`). 여기가 어긋나면 서버가 죽은 동안 고른
 * 답이 다른 항목으로 저장된다.
 */
/**
 * 장르 대분류 8종의 **화면용 한국어**. 서버 선택지를 못 받았을 때 쓰는 대체 목록이다.
 *
 * 서버는 `code_name`(영문, AI 서버가 세분류에 잇는 값)과 `display_name`(한국어)을
 * 함께 주고 화면은 `display_name || name` 을 그린다(`namesOf`). 여기 값은 그
 * `display_name` 과 같아야 한다 — 다르면 서버가 응답할 때와 못 할 때 글자가 달라진다.
 */
export const genreOptions = [
  '일렉트로닉',
  '인디',
  '알앤비',
  '록',
  '팝',
  '힙합',
  '재즈',
  '클래식',
];
/**
 * 무드 코드표 — 서버 `codes(MOOD).code_name`(영문 내부 코드) → 화면용 한국어.
 * **17종이다** (2026-09-17 추가, 2026-09-21 영문 코드로 정리).
 *
 * 서버는 곡·구간·추천 묶음의 무드를 **코드 이름(영문)** 으로 준다(`MusicSummary.moods`,
 * `DailyRecommendationResult.groups[].mood`). 화면에는 한국어를 그려야 하므로
 * 여기서 옮긴다 — 온보딩·탐색 선택지는 `display_name` 이 함께 내려와 이 표가
 * 필요 없다. 서버 `BootstrapRunner.CODE_SEEDS` 와 같은 값·같은 차례다.
 *
 * **앞 8개는 대분류, 뒤 9개는 taxonomy 세분류다.** taxonomy 무드 9종은 색인 축
 * (`mood_tags`)에 그 이름이 하나도 없어 뜻으로 이어야 했고
 * (`ai_server/app/onboarding.py` 의 `MOOD_MAP`), 세분류를 온보딩에 넣기로 하면서
 * 선택지가 8에서 17로 늘었다.
 */
export const moodDisplayNames: Record<string, string> = {
  serene: '잔잔한',
  uplifting: '벅찬',
  ethereal: '몽환적인',
  energetic: '신나는',
  focused: '집중되는',
  emotional: '감성적인',
  intense: '강렬한',
  romantic: '설레는',
  // taxonomy 무드 9종 (색인 어휘 → 한국어). 순서는 곡 수 많은 순이다.
  melancholic: '애상적인',
  warm: '따뜻한',
  dreamy: '몽글한',
  bright: '화사한',
  hopeful: '희망찬',
  powerful: '웅장한',
  dark: '어두운',
  calm: '고요한',
  exciting: '짜릿한',
};

/**
 * 서버가 준 무드 이름을 화면용 한국어로. 표에 없으면 그대로 돌려준다 — 배치가
 * 코드를 더 넣었을 수 있고, 그때 빈 칸보다는 코드 이름이 낫다. 이미 한국어인
 * 값(예전 저장분)도 그대로 통과한다.
 */
export const moodLabel = (name: string): string => moodDisplayNames[name] ?? name;

/** 위와 같은 대체 목록 — 무드 쪽 화면용 이름 17종. 서버 선택지를 못 받았을 때 쓴다.
 *
 * **두 층이 한 목록에 있다.** 대분류만 보이게 하려면 온보딩 화면에서
 * `moodOptions.slice(0, 8)` 을 쓰거나 서버 응답을 갈라야 한다. 지금은 온보딩의
 * 분위기 단계가 장르 단계와 분리돼 있어서(`WelcomeView` 의 `step === 1`) 섞이지
 * 않는다 — 층위가 문제가 되는 곳은 장르 단계(대분류 8 + 세분류 45)뿐이다.
 */
export const moodOptions = Object.values(moodDisplayNames);

export const formatTime = (seconds: number) => {
  if (!Number.isFinite(seconds)) return '00:00';
  seconds = Math.max(0, seconds);
  const minutes = Math.floor(seconds / 60);
  return `${String(minutes).padStart(2, '0')}:${String(Math.floor(seconds % 60)).padStart(2, '0')}`;
};

/**
 * 위치가 걸치는 구간. **언제나 구간 하나를 준다.**
 *
 * 구간이 비어 있을 수 있다 — 서버가 곡 길이를 모르면(`streams` 에 행이 없어
 * `duration_ms` 가 null) `stream-catalog` 가 나눌 기준이 없어 빈 배열을 넣는다.
 * 부르는 쪽은 다섯 군데 모두 결과의 `.start` 를 바로 읽으므로, 여기서
 * undefined 를 돌려주면 화면이 통째로 죽는다. 곡 전체를 한 구간으로 준다.
 */
export const getSongSectionAt = (track: Track, at: number): SongSection => {
  const position = Number.isFinite(at)
    ? Math.max(0, Math.min(at, track.duration))
    : 0;
  return (
    track.sections.find(
      (section, index) =>
        position >= section.start &&
        (position < section.end || index === track.sections.length - 1),
    ) ??
    track.sections[0] ?? {
      label: '전체',
      start: 0,
      end: Number.isFinite(track.duration) ? Math.max(0, track.duration) : 0,
    }
  );
};

/** `Track.mood`(화면용 한국어) → 에너지 1~5. 표에 없으면 3 으로 본다. */
const moodEnergy: Record<string, number> = {
  잔잔한: 1,
  감성적인: 2,
  몽환적인: 2,
  집중되는: 3,
  설레는: 4,
  벅찬: 4,
  신나는: 5,
  강렬한: 5,
  // taxonomy 9종
  애상적인: 2,
  따뜻한: 2,
  몽글한: 2,
  화사한: 4,
  희망찬: 4,
  웅장한: 5,
  어두운: 3,
  고요한: 1,
  짜릿한: 5,
};

export const chatSuggestions = [
  '이 곡보다 조금 더 잔잔하게',
  '분위기는 유지하고 더 벅차게',
  '보컬의 여운이 긴 음악',
  '집중하기 좋은 비트로 이어줘',
];

export function deriveChatRecommendations(
  prompt: string,
  // 아직 한 곡도 고르지 않았을 수 있다. 그때는 기준 곡 없이 고른다.
  currentTrack: Track | null,
  pool: Track[],
) {
  const normalized = prompt.toLowerCase();
  const wantsCalm = /잔잔|차분|편안|고요|느리|부드러/.test(normalized);
  const wantsEnergy = /벅차|터지|강렬|신나|에너지|빠르|고조/.test(normalized);
  const wantsVocal = /보컬|목소리|노래|감성|여운/.test(normalized);
  const wantsRhythm = /집중|비트|리듬|그루브|베이스/.test(normalized);
  const wantsSimilar = /비슷|유사|같은|유지/.test(normalized);
  const currentEnergy = (currentTrack ? moodEnergy[currentTrack.mood] : undefined) ?? 3;
  const targetEnergy = wantsCalm
    ? Math.max(1, currentEnergy - 1.5)
    : wantsEnergy
      ? Math.min(5, currentEnergy + 1.5)
      : currentEnergy;
  const candidates = pool.filter((track) => track.id !== currentTrack?.id);
  const ranked = candidates
    .map((track) => {
      const energy = moodEnergy[track.mood] ?? 3;
      const sharedTags = currentTrack
        ? track.tags.filter((tag) => currentTrack.tags.includes(tag)).length
        : 0;
      let score = 100 - Math.abs(energy - targetEnergy) * 16 + track.match / 12;
      if (currentTrack && track.genre === currentTrack.genre) score += wantsSimilar ? 14 : 6;
      score += sharedTags * (wantsSimilar ? 9 : 4);
      if (
        wantsVocal &&
        (/보컬|여운/.test(track.reason) ||
          ['R&B', 'Indie', 'Pop'].includes(track.genre))
      )
        score += 24;
      if (
        wantsRhythm &&
        (/리듬|그루브|비트|베이스/.test(track.reason) ||
          ['Hip-hop', 'Electronic'].includes(track.genre))
      )
        score += 24;
      if (
        wantsEnergy &&
        /빌드|쌓|열리|커지/.test(`${track.reason} ${track.tags.join(' ')}`)
      )
        score += 18;
      if (wantsCalm && ['잔잔한', '감성적인', '몽환적인', '고요한', '따뜻한'].includes(track.mood))
        score += 18;
      return { track, score };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, 3)
    .map(({ track }) => track);

  const direction = wantsCalm
    ? '공간감과 결은 남기고 에너지 밀도를 한 단계 낮춘 곡'
    : wantsEnergy
      ? '현재 분위기에서 전개와 에너지가 더 크게 열리는 곡'
      : wantsVocal
        ? '보컬의 질감과 긴 여운이 중심이 되는 곡'
        : wantsRhythm
          ? '리듬과 그루브가 또렷해 자연스럽게 집중되는 곡'
          : wantsSimilar
            ? '장르와 전개 방식이 닮은 곡'
            : '요청한 분위기와 현재 곡의 청취 맥락이 잘 이어지는 곡';

  return {
    text: !ranked.length
      ? '지금 추천할 수 있는 다른 곡이 없어요. 다른 음악을 선택하거나 탐색에서 더 찾아보세요.'
      : currentTrack
        ? `${currentTrack.title}의 ${currentTrack.mood} 무드를 기준으로, ${direction}을 골랐어요. 추천 순간부터 들어보세요.`
        : `${direction}을 골랐어요. 마음에 드는 곡부터 들어보세요.`,
    tracks: ranked,
  };
}

export type Page =
  | 'home'
  | 'search'
  | 'library'
  | 'report'
  | 'profile'
  | 'player'
  | 'moment'
  | 'similar'
  | 'welcome';
/** 데모 곡이 없으므로 미리 채워 둘 플레이리스트도 없다. */
export const initialPlaylists: Playlist[] = [];

/** 저장한 구간도 마찬가지다. 서버 API 가 생기기 전까지는 비어 있다. */
export const initialMoments: SavedMoment[] = [];
