'use strict';
// Merenje u pregledaču: koliko kupac čeka na otvaranje, korak 1, preporuku i
// pretragu. Ide samo našem serveru, bez naloga i bez sadržaja spiska: ime
// merenja, trajanje u milisekundama i verzija. Šalje se kad se kartica skloni
// ili kad se skupi 20 merenja.
const pkTiming = (() => {
  const app = document.querySelector('script[src^="app.js"]');
  const version = (app && new URL(app.src, location.href).searchParams.get('v')) || '?';
  const queue = [];
  const REQUESTS = [
    ['POST', /^shopping-lists\/\d+\/matching\?/, 'korak1'],
    ['GET', /^shopping-lists\/\d+\/recommendations\?/, 'preporuka'],
    ['GET', /^products\/search\?/, 'pretraga']
  ];

  function add(metric, ms) {
    if (!(ms >= 0) || ms > 600000) return;
    queue.push({ metric, durationMs: Math.round(ms) });
    if (queue.length >= 20) send();
  }

  function send() {
    if (!queue.length) return;
    const body = JSON.stringify({ appVersion: version, timings: queue.splice(0, 20) });
    try {
      if (navigator.sendBeacon && navigator.sendBeacon('/api/v1/web-timings', new Blob([body], { type: 'application/json' }))) return;
      fetch('/api/v1/web-timings', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body, keepalive: true })
        .catch(() => { /* merenje nije važnije od kupovine */ });
    } catch { /* isto */ }
  }

  addEventListener('visibilitychange', () => { if (document.visibilityState === 'hidden') send(); });
  addEventListener('pagehide', send);
  addEventListener('load', () => setTimeout(() => {
    const nav = performance.getEntriesByType && performance.getEntriesByType('navigation')[0];
    if (!nav) return;
    add('prvi-odgovor', nav.responseStart);
    add('otvaranje', nav.domContentLoadedEventEnd);
  }, 0));

  return {
    /** Zove ga api() posle uspešnog odgovora; ostali zahtevi se ne mere. */
    request(method, path, ms) {
      const match = REQUESTS.find(([m, pattern]) => m === method && pattern.test(path));
      if (match) add(match[2], ms);
    }
  };
})();
