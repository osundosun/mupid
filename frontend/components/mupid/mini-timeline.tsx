'use client';
import { useState } from 'react';
import { Field } from '@base-ui/react/field';
import { Slider } from '@/components/ui/slider';
import { formatTime } from '@/lib/mupid-data';
import type { MupidModel } from '@/hooks/use-mupid';

export function MiniTimeline({ model: m }: { model: MupidModel }) {
  const [draft, setDraft] = useState<{ id: number; time: number } | null>(null);
  const position = draft?.id === m.active.id ? draft.time : m.time;
  return (
    <Field.Root className="mini-timeline">
      <Field.Label className="sr-only">미니 플레이어 재생 위치</Field.Label>
      <span aria-label="현재 재생 시간">{formatTime(position)}</span>
      <Slider
        className="mini-seek"
        min={0}
        max={m.active.duration}
        value={[position]}
        step={1}
        onValueChange={(value) => setDraft({ id: m.active.id, time: Array.isArray(value) ? value[0] : value })}
        onValueCommitted={(value) => {
          m.seek(Array.isArray(value) ? value[0] : value);
          setDraft(null);
        }}
      />
      <span aria-label="전체 재생 시간">{formatTime(m.active.duration)}</span>
    </Field.Root>
  );
}
