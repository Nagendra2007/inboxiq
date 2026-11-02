import { useEffect, useState } from 'react';
import { DevicesApi, PushApi } from '../api/endpoints';
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
  const [appEnabled, setAppEnabled] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let live = true;
    currentPushState()
      .then((s) => live && setState(s))
      .catch(() => live && setState('unsupported'));
    // Separate channel, separate configuration: the Android app can be
    // dead while the browser works, and vice versa.
    DevicesApi.config()
      .then(({ enabled }) => live && setAppEnabled(enabled))
      .catch(() => live && setAppEnabled(false));
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

  async function sendAppTest() {
    setBusy(true);
    try {
      await DevicesApi.test();
      toast.success('Sent to your phone', 'If nothing arrives, the app has not registered itself.');
    } catch (err) {
      toast.error('Could not send', errorMessage(err, 'The server could not reach Firebase.'));
    } finally {
      setBusy(false);
    }
  }

  const androidSection = (
    <div className="space-y-2 border-t border-white/5 pt-4">
      <p className="text-sm font-medium text-white/70">The Android app</p>
      {appEnabled === null && <p className="text-xs text-white/40">Checking…</p>}
      {appEnabled === false && (
        <p className="text-sm leading-relaxed text-white/45">
          Not set up on this deployment. The app's notifications go through Firebase, which needs{' '}
          <span className="text-white/60">FCM_PROJECT_ID</span> and{' '}
          <span className="text-white/60">FCM_SERVICE_ACCOUNT_JSON</span> in the server's configuration — separately
          from the browser's VAPID keys above.
        </p>
      )}
      {appEnabled === true && (
        <>
          <p className="text-sm leading-relaxed text-white/55">
            Configured. A phone registers itself the first time you open the app signed in.
          </p>
          <Button onClick={sendAppTest} loading={busy} variant="ghost">
            Send a test to my phone
          </Button>
        </>
      )}
    </div>
  );

  if (state === null) return <p className="text-sm text-white/40">Checking…</p>;

  if (state === 'unsupported') {
    return (
      <div className="space-y-4">
        <p className="text-sm leading-relaxed text-white/45">
          This browser doesn't support notifications. On an iPhone, add Mailroom to your home screen first — iOS only
          delivers them to an installed app.
        </p>
        {androidSection}
      </div>
    );
  }

  if (state === 'unconfigured') {
    return (
      <div className="space-y-4">
        <p className="text-sm leading-relaxed text-white/45">
          Browser notifications aren't set up on this deployment. They need a VAPID key pair in the server's
          configuration.
        </p>
        {androidSection}
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <p className="text-sm leading-relaxed text-white/55">
        Mailroom notifies you about <span className="text-white/80">every email</span>, and the analysis writes the
        line it shows — what the email actually wants, in a dozen words, so you can tell from the lock screen whether
        it needs you. A <span className="text-white/80">one-time code</span> is shown large with a copy button.
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

      {androidSection}
    </div>
  );
}
