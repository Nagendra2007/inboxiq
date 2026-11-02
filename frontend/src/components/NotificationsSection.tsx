import { useEffect, useState } from 'react';
import { PushApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import { currentPushState, disablePush, enablePush, type PushState } from '../lib/push';
import { Button } from './ui/Button';
import { Alert } from './ui/Feedback';
import { useToast } from './ui/Toast';

/**
 * Turning on notifications, and nothing else turning them on.
 *
 * The permission prompt only ever comes from this button. A site that asks
 * on arrival is a site people block permanently, and a blocked site cannot
 * ask again — the browser remembers the refusal and there is no way back
 * except the user digging through settings themselves.
 *
 * Which is also why "denied" gets its own explanation rather than a dead
 * toggle: at that point the app genuinely cannot fix it.
 */
export function NotificationsSection() {
  const toast = useToast();
  const [state, setState] = useState<PushState | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let live = true;
    currentPushState()
      .then((s) => live && setState(s))
      .catch(() => live && setState('unsupported'));
    return () => {
      live = false;
    };
  }, []);

  async function toggle() {
    setBusy(true);
    setError('');
    try {
      const next = state === 'on' ? await disablePush() : await enablePush();
      setState(next);
      if (next === 'on') toast.success('Notifications on', 'You can send yourself a test below.');
      if (next === 'denied') {
        setError(
          'Your browser has blocked notifications for this site. Click the padlock beside the address bar to allow them, then try again.'
        );
      }
    } catch (err) {
      setError(errorMessage(err, 'Could not change notification settings.'));
    } finally {
      setBusy(false);
    }
  }

  async function sendTest() {
    setBusy(true);
    try {
      await PushApi.test();
      toast.success('Sent', 'It should appear in a moment.');
    } catch (err) {
      toast.error('Could not send', errorMessage(err, 'Try turning notifications off and on again.'));
    } finally {
      setBusy(false);
    }
  }

  if (state === null) return <p className="text-sm text-white/40">Checking…</p>;

  if (state === 'unsupported') {
    return (
      <p className="text-sm leading-relaxed text-white/45">
        This browser doesn't support notifications. On an iPhone, add Mailroom to your home screen first — iOS only
        delivers them to an installed app.
      </p>
    );
  }

  if (state === 'unconfigured') {
    return (
      <p className="text-sm leading-relaxed text-white/45">
        Notifications aren't set up on this deployment. They need a VAPID key pair in the server's configuration.
      </p>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed text-white/55">
        Mailroom will notify you about mail it marks <span className="text-white/80">high priority</span>, and anything
        it flags as a <span className="text-white/80">possible scam</span>. Nothing else — an app that buzzes for every
        email is one you turn off.
      </p>

      <div className="flex flex-wrap items-center gap-3">
        <Button onClick={toggle} loading={busy} variant={state === 'on' ? 'secondary' : 'primary'}>
          {state === 'on' ? 'Turn off notifications' : 'Turn on notifications'}
        </Button>
        {state === 'on' && (
          <Button onClick={sendTest} loading={busy} variant="ghost">
            Send a test
          </Button>
        )}
      </div>

      {state === 'on' && (
        <p className="text-xs text-white/40">
          This device only. Turn them on separately on your phone or another browser.
        </p>
      )}

      {error && <Alert tone="warning">{error}</Alert>}
    </div>
  );
}
