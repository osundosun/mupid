import type { CSSProperties } from 'react';
import { Sparkles } from 'lucide-react';

/** Transparent side-profile artwork shared by in-app mascot placements. */
export function MupidMascot({
  size = 96,
  className = '',
  alt = '',
}: {
  size?: number;
  className?: string;
  alt?: string;
}) {
  return (
    <span
      role={alt ? 'img' : undefined}
      aria-label={alt || undefined}
      aria-hidden={alt ? undefined : true}
      className={`mupid-mascot ${className}`.trim()}
      style={{ '--mascot-size': `${size}px` } as CSSProperties}
    />
  );
}

/** A decorative, non-interactive accent using the same supplied artwork. */
export function MascotAccent({
  size = 64,
  className = '',
}: {
  size?: number;
  className?: string;
}) {
  return (
    <span
      className={`mascot-accent ${className}`.trim()}
      style={{ '--accent-size': `${size}px` } as CSSProperties}
      aria-hidden="true"
    >
      <MupidMascot size={size} />
      <Sparkles className="mascot-sparkle" />
    </span>
  );
}
