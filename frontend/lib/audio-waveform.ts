const cache = new Map<string, Promise<number[]>>();

/** Decode local audio once; retain only its small amplitude envelope. */
export function loadAudioWaveform(url: string, count: number): Promise<number[]> {
  const key = `${url}:${count}`;
  const existing = cache.get(key);
  if (existing) return existing;
  const request = (async () => {
    const response = await fetch(url);
    if (!response.ok) throw new Error('Waveform audio unavailable');
    const context = new OfflineAudioContext(1, 1, 22050);
    const audio = await context.decodeAudioData(await response.arrayBuffer());
    const channels = Array.from({ length: audio.numberOfChannels }, (_, i) => audio.getChannelData(i));
    const values = Array.from({ length: count }, (_, index) => {
      const start = Math.floor(index * audio.length / count);
      const end = Math.floor((index + 1) * audio.length / count);
      let energy = 0;
      for (const channel of channels) {
        for (let i = start; i < end; i++) energy += channel[i] * channel[i];
      }
      return Math.sqrt(energy / Math.max(1, (end - start) * channels.length));
    });
    const peak = Math.max(...values, .00001);
    return values.map(value => 4 + 88 * Math.pow(value / peak, .8));
  })();
  cache.set(key, request);
  request.catch(() => cache.delete(key));
  return request;
}
