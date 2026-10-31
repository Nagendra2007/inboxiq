import { cn } from '../../lib/cn';

/**
 * Seven bars that move with what the microphone is actually hearing, rather
 * than a canned animation that looks identical whether or not the mic works.
 * That distinction is the point: the commonest dictation failure is a muted
 * or wrong input device, and a meter sitting flat while you talk says so
 * immediately, where a decorative pulse would cheerfully imply all is well.
 *
 * Each bar takes a different fraction of the level so the shape reads as a
 * waveform rather than a row of identical blocks, and the CSS transition
 * does the smoothing between the ~20 updates a second it is given.
 */

// Centre bars respond most, which is how a voice meter is expected to look.
const WEIGHTS = [0.35, 0.6, 0.85, 1, 0.85, 0.6, 0.35];

export function VoiceWave({ level, className }: { level: number; className?: string }) {
  return (
    <span
      className={cn('inline-flex items-center gap-[3px]', className)}
      aria-hidden="true"
    >
      {WEIGHTS.map((weight, i) => {
        // The floor is 4 rather than 3 so a resting bar is still a short
        // line: at 3px on a 3px-wide bar it rounds into a dot, and a row of
        // dots reads as a loading indicator rather than a level meter.
        const height = 4 + Math.round(level * weight * 18);
        return (
          <span
            key={i}
            className="w-[3px] rounded-full bg-current transition-[height] duration-100 ease-out"
            style={{ height: `${height}px` }}
          />
        );
      })}
    </span>
  );
}
