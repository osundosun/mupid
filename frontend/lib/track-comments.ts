export type TrackComment = { id: string; trackId: number; at: number; body: string; author: string; createdAt: number };
const key = 'mupid.track-comments.v1';
export function validateComment(body: string, at: number, duration: number) {
  return !!body.trim() && body.trim().length <= 500 && Number.isFinite(duration) && Number.isFinite(at) && at >= 0 && at <= duration;
}
/** Device-local UI adapter. Real author identity and authorization must come from the server later. */
export const trackComments = {
  // 프론트 체험용 저장 경계. 추후 곡별 조회·등록·수정·삭제 API로 대체하며 사용자 ID를 서버에서 확인한다.
  async list(): Promise<TrackComment[]> {
    const raw: unknown = JSON.parse(localStorage.getItem(key) || '[]');
    if (!Array.isArray(raw)) throw new Error('Invalid comments');
    return raw.filter((item): item is TrackComment => item && typeof item.id === 'string' && Number.isFinite(item.trackId) && Number.isFinite(item.at) && item.at >= 0 && typeof item.body === 'string' && !!item.body.trim() && item.body.length <= 500 && typeof item.author === 'string' && Number.isFinite(item.createdAt) && Math.abs(item.createdAt) <= 8640000000000000);
  },
  async save(items: TrackComment[]) { localStorage.setItem(key, JSON.stringify(items)); },
};
