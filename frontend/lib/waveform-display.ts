/** 실제 RMS 값의 강약을 강조하되 시간 순서와 무음을 보존한다.
 * 모든 곡에 같은 곡선(v²)을 적용한다. 곡 ID나 난수로 무늬를 만들지 않는다.
 */
export function resampleWaveform(values: number[], count: number): number[] {
  if (!values.length || !Number.isInteger(count) || count <= 0) return [];
  const clean = values.map(v => Number.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0);
  const n = clean.length;
  return Array.from({ length: count }, (_, i) => {
    let v: number;
    if (count <= n) {
      const from = Math.floor(i * n / count);
      const to = Math.max(from + 1, Math.floor((i + 1) * n / count));
      let sum = 0;
      for (let k = from; k < to; k++) sum += clean[k];
      v = sum / (to - from);
    } else {
      const pos = i * (n - 1) / (count - 1);
      const lo = Math.floor(pos), hi = Math.min(n - 1, lo + 1), t = pos - lo;
      v = clean[lo] * (1 - t) + clean[hi] * t;
    }
    return 4 + 88 * v * v;
  });
}
