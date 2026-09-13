import { useEffect, useState } from 'react';
import { AdminApi } from '../api/endpoints';
import { errorMessage } from '../api/client';
import type { AdministratorsDto } from '../types';
import { cn } from '../lib/cn';
import { Avatar } from './ui/Avatar';
import { Alert } from './ui/Feedback';

/** Admin-only: who can change app-wide settings, and why. */
export function AdministratorsSection({ currentEmail }: { currentEmail: string }) {
  const [data, setData] = useState<AdministratorsDto | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    AdminApi.administrators()
      .then(setData)
      .catch((err) => setError(errorMessage(err, 'Could not load the administrators.')));
  }, []);

  return (
    <section className="rounded-2xl border border-white/[0.06] bg-ink-800 p-5 md:p-6">
      <div className="flex items-center gap-2">
        <h2 className="text-sm font-semibold text-white/90">Administrators</h2>
        <span className="rounded-full bg-accent-500/10 px-2 py-0.5 text-2xs font-semibold text-accent-300">Admin</span>
      </div>
      <p className="mt-1 text-sm leading-relaxed text-white/45">
        {data?.source === 'ADMIN_EMAILS'
          ? 'Set by the ADMIN_EMAILS setting on the server. Change it in Render → Environment.'
          : 'No ADMIN_EMAILS setting on the server, so the first account that signed in is the administrator. Set ADMIN_EMAILS in Render → Environment to pin it or add people.'}
      </p>

      {error && (
        <Alert tone="danger" className="mt-4">
          {error}
        </Alert>
      )}

      {!data && !error && <span className="skeleton mt-4 h-12 w-full rounded-xl" aria-hidden="true" />}

      {data && (
        <ul className="mt-4 divide-y divide-white/[0.05] rounded-xl border border-white/[0.06]">
          {data.admins.map((admin) => {
            const isYou = admin.email.toLowerCase() === currentEmail.toLowerCase();
            return (
              <li key={admin.email} className="flex items-center gap-3 px-4 py-3">
                <Avatar name={admin.email.split('@')[0]} seed={admin.email} size="sm" />
                <span className="min-w-0 flex-1 truncate text-sm text-white/85">
                  {admin.email}
                  {isYou && <span className="ml-2 text-xs text-white/40">(you)</span>}
                </span>
                <span
                  className={cn(
                    'shrink-0 rounded-full px-2.5 py-1 text-2xs font-medium',
                    admin.signedIn ? 'bg-emerald-400/10 text-emerald-200' : 'bg-white/[0.05] text-white/45'
                  )}
                >
                  {admin.signedIn ? 'Has signed in' : "Hasn't signed in yet"}
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
