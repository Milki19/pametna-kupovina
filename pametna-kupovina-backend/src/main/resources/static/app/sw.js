// Da web radi i u prodavnici bez signala: stranica i njene skripte ostaju u
// pregledaču, a kupovina po planu je ionako sačuvana u njemu. Podaci sa
// servera (/api) se nikad ne keširaju ovde.
const CACHE = 'pk-app';

/**
 * Sačuva stranicu i sve što ona učitava (sa svojim ?v=) i obriše stare
 * verzije. Ako nešto ne stigne, ostaje prethodna potpuna kopija.
 */
async function keep(page) {
  const html = await page.clone().text();
  const files = [...new Set([...html.matchAll(/(?:src|href)="([^"#:]+)"/g)].map(m => m[1]))];
  const cache = await caches.open(CACHE);
  const missing = [];
  for (const file of files) {
    if (!(await cache.match(file))) missing.push(file);
  }
  await cache.addAll(missing);
  await cache.put('index.html', page);
  const wanted = new Set(['index.html', ...files].map(f => new URL(f, self.registration.scope).href));
  for (const request of await cache.keys()) {
    if (!wanted.has(request.url)) await cache.delete(request);
  }
}

self.addEventListener('install', event => {
  event.waitUntil(
    fetch('index.html', { cache: 'no-cache' })
      .then(page => page.ok ? keep(page) : null)
      .catch(() => {})
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', event => event.waitUntil(self.clients.claim()));

self.addEventListener('fetch', event => {
  const request = event.request;
  const url = new URL(request.url);
  if (request.method !== 'GET' || url.origin !== location.origin || !url.pathname.startsWith('/app/')) return;

  if (request.mode === 'navigate') {
    // Prvo mreža, da nova verzija stigne odmah; bez mreže sačuvana stranica.
    event.respondWith(
      fetch(request)
        .then(page => {
          if (page.ok && url.pathname.match(/^\/app\/(index\.html)?$/)) {
            event.waitUntil(keep(page.clone()).catch(() => {}));
          }
          return page;
        })
        .catch(async () => (await caches.match('index.html')) || Response.error())
    );
    return;
  }
  // Skripte nose ?v=, pa sačuvana kopija važi dok se verzija ne promeni.
  event.respondWith(caches.match(request).then(found => found || fetch(request)));
});
