export type TrackComment = { id: string; trackId: number; at: number; body: string; author: string; createdAt: number };
const key = 'mupid.track-comments.v1';
export function validateComment(body: string, at: number, duration: number) {
  return !!body.trim() && body.trim().length <= 500 && Number.isFinite(duration) && Number.isFinite(at) && at >= 0 && at <= duration;
}
/** Device-local UI adapter. Real author identity and authorization must come from the server later. */
export const trackComments = {
  async list(): Promise<TrackComment[]> {
    const raw: unknown = JSON.parse(localStorage.getItem(key) || '[]');
    if (!Array.isArray(raw)) throw new Error('Invalid comments');
    return raw.filter((item): item is TrackComment => item && typeof item.id === 'string' && Number.isFinite(item.trackId) && Number.isFinite(item.at) && item.at >= 0 && typeof item.body === 'string' && !!item.body.trim() && item.body.length <= 500 && typeof item.author === 'string' && Number.isFinite(item.createdAt) && Math.abs(item.createdAt) <= 8640000000000000);
  },
  async save(items: TrackComment[]) { localStorage.setItem(key, JSON.stringify(items)); },
};
