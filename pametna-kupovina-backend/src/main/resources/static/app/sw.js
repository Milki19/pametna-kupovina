// Da web radi i u prodavnici bez signala: stranica i njene skripte ostaju u
// pregledaču, a kupovina po planu je ionako sačuvana u njemu. Podaci sa
// servera (/api) se nikad ne keširaju ovde.
const CACHE = 'pk-app';

/**
 * Sačuva stranicu i sve što ona učitava (sa svojim ?v=), pa i delove ekrana
 * koje app.js učitava sa import() (chunks/), i obriše stare verzije. Ako
 * nešto ne stigne, ostaje prethodna potpuna kopija.
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
  const scope = self.registration.scope;
  const wanted = new Set(['index.html', ...files].map(f => new URL(f, scope).href));
  // Delovi nose hash u imenu, pa sačuvan deo važi dok ga app.js traži.
  const scripts = files.filter(f => /\.js(\?|$)/.test(f)).map(f => new URL(f, scope).href);
  for (let i = 0; i < scripts.length; i++) {
    const found = await cache.match(scripts[i]);
    if (!found) continue;
    for (const [, path] of (await found.text()).matchAll(/["'`](\.{1,2}\/[^"'`\s?#]+\.js)["'`]/g)) {
      const url = new URL(path, scripts[i]).href;
      if (wanted.has(url)) continue;
      wanted.add(url);
      if (!(await cache.match(url))) await cache.add(url);
      scripts.push(url);
    }
  }
  await cache.put('index.html', page);
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
  // Skripte nose ?v=, a delovi ekrana hash u imenu, pa sačuvana kopija važi
  // dok se verzija ne promeni. Deo koji još nije sačuvan čuva se kad stigne.
  event.respondWith(caches.match(request).then(found => found || fetch(request).then(response => {
    if (response.ok && url.pathname.startsWith('/app/chunks/')) {
      const copy = response.clone();
      event.waitUntil(caches.open(CACHE).then(cache => cache.put(request, copy)).catch(() => {}));
    }
    return response;
  })));
});
