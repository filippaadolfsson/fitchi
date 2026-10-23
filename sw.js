// Fitchi – enkel offline-cache
const CACHE = 'fitchi-v1';
const FILER = ['./', 'index.html', 'manifest.webmanifest', 'img/ikon.svg', 'img/ikon-192.png',
  'img/bjorn.webp', 'img/hare.webp', 'img/uggla.webp', 'img/utter.webp', 'img/katt.webp'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILER))); self.skipWaiting(); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(k => Promise.all(k.filter(x => x !== CACHE).map(x => caches.delete(x))))); self.clients.claim(); });
self.addEventListener('fetch', e => {
  if(e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if(url.origin !== location.origin){ return; }
  // sidan: nätet först så att uppdateringar syns, bilder: cache först
  if(e.request.mode === 'navigate' || url.pathname.endsWith('.html')){
    e.respondWith(fetch(e.request).then(r => { const k = r.clone(); caches.open(CACHE).then(c => c.put(e.request, k)); return r; }).catch(() => caches.match(e.request).then(r => r || caches.match('index.html'))));
  } else {
    e.respondWith(caches.match(e.request).then(r => r || fetch(e.request).then(n => { const k = n.clone(); caches.open(CACHE).then(c => c.put(e.request, k)); return n; })));
  }
});
