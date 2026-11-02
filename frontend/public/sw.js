/*
 * Mailroom's service worker.
 *
 * It exists for one reason: a browser will only deliver a push notification
 * to a service worker, so there has to be one. It deliberately does NOT
 * cache anything and has no fetch handler.
 *
 * That restraint is the point. A caching service worker is the commonest way
 * to break a working site: it serves yesterday's bundle to someone who just
 * reloaded, and because it controls the page it can keep doing so after the
 * cache is fixed. Mailroom already has good caching — hashed assets served
 * immutable for a year, index.html no-cache — handled by the server, which
 * is the right place for it. Nothing here should second-guess that.
 *
 * So the site behaves identically whether or not this file is registered.
 */

// Take over as soon as a new version is installed, rather than waiting for
// every tab to close. Safe precisely because nothing is cached: there is no
// stale state for a new worker to inherit.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  let payload = {};
  try {
    payload = event.data ? event.data.json() : {};
  } catch {
    // A push that is not JSON is still worth showing rather than dropping.
    payload = { body: event.data ? event.data.text() : '' };
  }

  const title = payload.title || 'Mailroom';
  const options = {
    body: payload.body || '',
    icon: '/icon-192.png',
    badge: '/icon-192.png',
    // Replaces an earlier notification with the same tag instead of stacking
    // five of them, which is how a useful app becomes a muted one.
    tag: payload.tag || 'mailroom',
    renotify: Boolean(payload.tag),
    timestamp: Date.now(),
    data: { url: payload.url || '/inbox' },
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = (event.notification.data && event.notification.data.url) || '/inbox';

  // Focus a tab that is already open rather than opening a fifth one.
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((windows) => {
      for (const client of windows) {
        if (client.url.includes(self.registration.scope) && 'focus' in client) {
          client.navigate(target).catch(() => {});
          return client.focus();
        }
      }
      return self.clients.openWindow(target);
    })
  );
});
