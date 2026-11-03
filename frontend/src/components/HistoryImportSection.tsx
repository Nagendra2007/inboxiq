import { useEffect, useState } from 'react';
import { BackfillApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import { useRealtime } from '../context/RealtimeContext';
import { Button } from './ui/Button';
import { Alert } from './ui/Feedback';

/**
 * Importing older mail.
 *
 * Signing in deliberately fetches only the newest messages, so that it is
 * quick. That is right for an inbox and wrong for anything that searches:
 * a question about last term's fees is a question about mail that was
 * never stored. This is where the rest comes in.
 *
 * Opt-in rather than automatic, because it is thousands of calls against a
 * Gmail quota shared with the ordinary sync, and only worth spending when
 * somebody actually wants their history searchable.
 */

const RANGES = [
  { months: 6, label: '6 months' },
  { months: 12, label: '1 year' },
  { months: 36, label: '3 years' },
  { months: 0, label: 'Everything' },
];

export function HistoryImportSection() {
  const [running, setRunning] = useState(false);
  const [stored, setStored] = useState(0);
  const [finished, setFinished] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const realtime = useRealtime();

  useEffect(() => {
    BackfillApi.progress()
      .then((progress) => {
        setRunning(progress.running);
        setStored(progress.stored);
        setFinished(progress.finishedAt);
      })
      .catch(() => {});
  }, []);

  // The import runs for minutes on a real mailbox, so the count comes over
  // the same stream as everything else rather than from polling.
  useEffect(() => {
    return realtime.subscribe('mail.backfill.progress', (payload) => {
      const data = payload as { stored?: number; done?: boolean };
      if (typeof data.stored === 'number') setStored(data.stored);
      if (data.done) {
        setRunning(false);
        setFinished(new Date().toISOString());
      }
    });
  }, [realtime]);

  async function start(months: number) {
    setBusy(true);
    setError('');
    try {
      const result = await BackfillApi.start(months);
      setRunning(result.started || running);
      setFinished(null);
    } catch (err) {
      setError(errorMessage(err, 'Could not start the import.'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed text-white/55">
        Signing in fetches only your newest mail, so it is quick. Import more to make older mail searchable — and to
        give <span className="text-white/80">Ask</span> something to find.
      </p>

      {running ? (
        <div className="flex items-center gap-2 text-sm text-white/60">
          <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-accent-400" />
          Importing — <span className="tabular-nums text-white/80">{stored.toLocaleString()}</span> messages so far.
          You can leave this page.
        </div>
      ) : (
        <div className="flex flex-wrap gap-2">
          {RANGES.map((range) => (
            <Button key={range.months} variant="secondary" loading={busy} onClick={() => start(range.months)}>
              {range.label}
            </Button>
          ))}
        </div>
      )}

      {!running && stored > 0 && (
        <p className="text-xs text-white/40">
          {stored.toLocaleString()} messages imported{finished ? '.' : ' so far — start again to continue.'}
        </p>
      )}

      <p className="text-xs leading-relaxed text-white/35">
        Imported mail is not analysed until something needs it, so this costs Gmail requests rather than AI usage. It
        can be stopped and resumed at any time.
      </p>

      {error && <Alert tone="warning">{error}</Alert>}
    </div>
  );
}
