'use client';
import { useState } from 'react';
import { Field } from '@base-ui/react/field';
import { Slider } from '@/components/ui/slider';
import { formatTime } from '@/lib/mupid-data';
import type { MupidModel } from '@/hooks/use-mupid';

export function MiniTimeline({ model: m }: { model: MupidModel }) {
  const [draft, setDraft] = useState<{ id: number; time: number } | null>(null);
  const active = m.active;
  // 들을 곡이 없으면 막대도 없다. 곡과 끌던 값이 둘 다 없으면 `undefined ===
  // undefined` 가 참이 되어 없는 `draft` 를 읽으려 들기도 했다.
  if (!active) return null;
  const position = draft?.id === active.id ? draft.time : m.time;
  return (
    <Field.Root className="mini-timeline">
      <Field.Label className="sr-only">미니 플레이어 재생 위치</Field.Label>
      <span aria-label="현재 재생 시간">{formatTime(position)}</span>
      <Slider
        className="mini-seek"
        min={0}
        max={active.duration}
        value={[position]}
        step={1}
        onValueChange={(value) => setDraft({ id: active.id, time: Array.isArray(value) ? value[0] : value })}
        onValueCommitted={(value) => {
          m.seek(Array.isArray(value) ? value[0] : value);
          setDraft(null);
        }}
      />
      <span aria-label="전체 재생 시간">{formatTime(active.duration)}</span>
    </Field.Root>
  );
}
