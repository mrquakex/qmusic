// Nuvelo Web's service worker: the app's own files kept on the device (it opens at once, and without a connection),
// and the notifications of new messages (Firebase Cloud Messaging sends them here as data; this shows them).
const CACHE = 'nuvelo-v2';
const SCOPE = new URL('./', self.location).pathname; // "/web/"

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['./', 'index.html', 'nuvelo.js', 'manifest.webmanifest', 'icon-180.png', 'icon-192.png'])).catch(() => {}));
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k.startsWith('nuvelo-') && k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const req = e.request;
  const url = new URL(req.url);
  // Only the app's own files: the messages' media and the server's answers are never kept here.
  if (req.method !== 'GET' || url.origin !== location.origin || !url.pathname.startsWith(SCOPE)) return;
  const page = req.mode === 'navigate' || url.pathname === SCOPE || url.pathname.endsWith('/index.html') || url.pathname.endsWith('/nuvelo.js') || url.pathname.endsWith('/sw.js');
  if (page) {
    // The newest first; the kept copy when there is no connection.
    e.respondWith(fetch(req).then((res) => {
      if (res.ok) {
        const copy = res.clone();
        caches.open(CACHE).then((c) => c.put(req, copy));
      }
      return res;
    }).catch(() => caches.match(req).then((r) => r || caches.match('index.html'))));
    return;
  }
  // The big files (the app's code, the fonts) have names that change with them: the kept copy, fetched once.
  e.respondWith(caches.match(req).then((hit) => hit || fetch(req).then((res) => {
    if (res.ok) {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(req, copy));
    }
    return res;
  })));
});

// ------------------------------------------------------------------ notifications

/** A preview's mark said in words, as the phones' notifications do. */
const MARKS = [['🎤', 'Sesli mesaj'], ['🎬', 'Video'], ['📄', 'Dosya'], ['📷', 'Fotoğraf'], ['📍', 'Konum']];
function plain(text) {
  const t = String(text || '').trim();
  for (const [mark, word] of MARKS) if (t.startsWith(mark)) return (t.slice(mark.length).trim() || word);
  return t;
}

self.addEventListener('push', (e) => {
  let p = {};
  try { p = e.data ? e.data.json() : {}; } catch { p = {}; }
  const d = p.data || p;
  e.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const here = windows.some((w) => w.visibilityState === 'visible' && w.focused);
    const iOS = /iPhone|iPad|iPod/.test(self.navigator.userAgent);
    // The page is open in front of the person: it shows the message itself (iPhones must be shown something).
    if (here && !iOS) return;
    let title = d.name || 'Nuvelo';
    let body = String(d.body || '');
    if (body.startsWith('e2e:')) body = 'Yeni mesaj';
    body = plain(body);
    if (d.type === 'call') {
      if (d.action === 'cancel') return self.registration.getNotifications({ tag: 'call-' + d.callId }).then((l) => l.forEach((n) => n.close()));
      body = (d.video === '1' ? 'Görüntülü arıyor' : 'Arıyor') + ' · telefonundan aç';
    } else if (d.type === 'friend') {
      body = d.action === 'accepted' ? 'Arkadaşlık isteğini kabul etti' : 'Sana arkadaşlık isteği gönderdi';
    } else if (d.type === 'announce') {
      title = 'Nuvelo';
    }
    if (!body) body = 'Yeni mesaj';
    await self.registration.showNotification(title, {
      body,
      icon: d.photo || 'icon-192.png',
      badge: 'icon-192.png',
      tag: d.type === 'call' ? 'call-' + d.callId : (d.chatId || 'nuvelo'),
      renotify: true,
      silent: here,
      data: { chatId: d.chatId || '', sender: d.sender || '', name: d.name || '', username: d.username || '', photo: d.photo || '' },
    });
  })());
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const d = e.notification.data || {};
  e.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const w = windows.find((x) => new URL(x.url).pathname.startsWith(SCOPE)) || windows[0];
    if (w) {
      await w.focus();
      w.postMessage({ nuvelo: 'open', ...d });
      return;
    }
    const q = new URLSearchParams({ open: d.sender || '', name: d.name || '', username: d.username || '', photo: d.photo || '' });
    await self.clients.openWindow(SCOPE + '?' + q.toString());
  })());
});
