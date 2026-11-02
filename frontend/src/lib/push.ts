import { PushApi } from '../api/endpoints';

/**
 * Browser notifications.
 *
 * The service worker is registered on every load, because a push cannot be
 * delivered without one and the registration has to already exist by the
 * time a push arrives. Subscribing — which is what triggers the permission
 * prompt — happens only when the user asks for it in Settings. A site that
 * asks for notification permission on arrival is a site people block.
 */

/**
 * The VAPID key arrives base64url; PushManager wants raw bytes.
 *
 * Returns an ArrayBuffer rather than the Uint8Array it is built from,
 * because TypeScript's BufferSource will not accept a view whose backing
 * buffer might be a SharedArrayBuffer.
 */
function toBytes(base64url: string): ArrayBuffer {
  const padded = (base64url + '='.repeat((4 - (base64url.length % 4)) % 4))
    .replace(/-/g, '+')
    .replace(/_/g, '/');
  const raw = atob(padded);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes.buffer;
}

/** ArrayBuffer key out of a PushSubscription, as the base64url the server stores. */
function keyOf(subscription: PushSubscription, name: 'p256dh' | 'auth'): string {
  const buffer = subscription.getKey(name);
  if (!buffer) return '';
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function pushSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window
  );
}

/**
 * Registers the worker. Safe to call on every load — the browser keeps one
 * registration per scope and simply updates it.
 */
export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!pushSupported()) return null;
  try {
    return await navigator.serviceWorker.register('/sw.js', { scope: '/' });
  } catch {
    // An unregistrable worker costs nothing: the app has no other use for it.
    return null;
  }
}

export type PushState = 'unsupported' | 'unconfigured' | 'denied' | 'off' | 'on';

export async function currentPushState(): Promise<PushState> {
  if (!pushSupported()) return 'unsupported';
  const { enabled } = await PushApi.config().catch(() => ({ enabled: false, publicKey: null }));
  if (!enabled) return 'unconfigured';
  if (Notification.permission === 'denied') return 'denied';
  const registration = await navigator.serviceWorker.getRegistration('/');
  const existing = await registration?.pushManager.getSubscription();
  return existing ? 'on' : 'off';
}

/** Asks permission, subscribes, and tells the server. Returns the new state. */
export async function enablePush(): Promise<PushState> {
  if (!pushSupported()) return 'unsupported';
  const { enabled, publicKey } = await PushApi.config();
  if (!enabled || !publicKey) return 'unconfigured';

  const permission = await Notification.requestPermission();
  if (permission !== 'granted') return permission === 'denied' ? 'denied' : 'off';

  const registration = (await navigator.serviceWorker.getRegistration('/')) ?? (await registerServiceWorker());
  if (!registration) return 'off';
  // The worker has to be active before it can take a subscription.
  await navigator.serviceWorker.ready;

  const subscription = await registration.pushManager.subscribe({
    // Non-visible pushes are refused by every browser that matters, and
    // ours always shows a notification anyway.
    userVisibleOnly: true,
    applicationServerKey: toBytes(publicKey),
  });

  await PushApi.subscribe({
    endpoint: subscription.endpoint,
    p256dh: keyOf(subscription, 'p256dh'),
    auth: keyOf(subscription, 'auth'),
  });
  return 'on';
}

export async function disablePush(): Promise<PushState> {
  const registration = await navigator.serviceWorker.getRegistration('/');
  const subscription = await registration?.pushManager.getSubscription();
  if (subscription) {
    // Tell the server first: if unsubscribing locally succeeded and the
    // call failed, the server would keep pushing into a dead endpoint.
    await PushApi.unsubscribe(subscription.endpoint).catch(() => {});
    await subscription.unsubscribe().catch(() => {});
  }
  return 'off';
}
