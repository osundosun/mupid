import type { Track } from '@/lib/mupid-data';

export function RecommendationReason({ track }: { track: Track }) {
  const reason = (track.recommended?.reason || track.recommendationReason)?.trim();
  if (!reason) return null;
  return <p className="recommendation-reason"><span>추천 이유</span>{reason}</p>;
}
