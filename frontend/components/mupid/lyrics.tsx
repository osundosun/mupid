import type { Track } from '@/lib/mupid-data';

const drafts: Record<string, string[]> = {
  '푸른 오후': ['느린 빛이 창가에 내려', '잠시 멈춘 하루를 안아', '너와 걷던 푸른 오후', '아직 내 마음에 머물러'],
  '구름 뒤의 산책': ['구름 뒤에 길을 그려', '이름 없는 바람을 따라', '서두르지 않아도 좋아', '우리 걸음이 길이 되니까'],
  '새벽의 온도': ['아무도 깨지 않은 거리', '너의 목소리만 따뜻해', '차가운 새벽을 건너', '작은 빛으로 내게 와'],
  '모든 날, 모든 순간': ['흩어진 날들을 모으면', '그 안에 네 웃음이 있어', '지나간 모든 순간들이', '오늘의 우리를 비추네'],
  '우리의 계절': ['바람이 옷깃을 바꾸면', '너와의 계절이 돌아와', '조금은 달라진 우리도', '같은 하늘 아래 피어나'],
  '조금만, 더': ['돌아서는 밤의 끝에서', '못다 한 말을 꺼내어 봐', '조금만 더 여기 있어 줘', '이 노래가 끝날 때까지'],
  '후회 밖의 우리': ['어제의 문을 닫고 나와', '새로운 이름을 불러 봐', '후회가 닿지 않는 곳에', '우리의 내일을 놓아둘게'],
  '비 오는 날의 우리': ['우산 끝에 맺힌 이야기', '젖은 골목을 함께 걸어', '비가 그친 뒤에도 우리', '같은 속도로 걸어가자'],
};

// 접지 않고 항상 보여 준다. LP 바로 아래에 놓이는 블록이다.
export function Lyrics({ track }: { track: Track }) {
  const lines = drafts[track.title] ?? [];
  return <section className="song-lyrics" key={track.id} aria-label={`${track.title} 가사`}>
    <h3>{track.title}</h3>
    {lines.length
      ? lines.map(line => <p key={line}>{line}</p>)
      : <p className="song-lyrics-empty">가사가 아직 준비되지 않았어요.</p>}
    <p className="lyrics-disclaimer">곡 분위기로 작성한 임시 가사 · 실제 음원 가사와 다를 수 있어요</p>
  </section>;
}
