// Web verzija za telefone bez Android aplikacije (iPhone). Isti server i isti
// nalog po uređaju kao Android: pregledač pamti nasumičan ključ, jednom ga
// menja za sesiju i dalje šalje samo kratak pristupni token. Napredak kupovine
// ostaje u pregledaču, kao u aplikaciji.
//
// Ulaz za esbuild: static/app/app.js nosi jezgro (core/) i Spisak, a ostali
// ekrani su posebni delovi u static/app/chunks/, koje ruter učitava sa
// import() kad zatrebaju (vidi src/main/web/build.mjs i core/router.js).

import { route } from './core/router.js';
import { refreshMarket } from './core/format.js';
import { toast } from './core/screen.js';

// Kartica otvorena danima, ili stranica iz keša kad mreža nije stigla, vrti
// staru verziju. Kad server ima noviju, ona se učita na sledećem prelazu
// između ekrana, kad nema ničeg nesačuvanog.
const loadedScripts = new URL(import.meta.url).search;
let newerVersion = false;
let versionCheckedAt = 0;

async function checkVersion() {
  if (newerVersion || Date.now() - versionCheckedAt < 30 * 60 * 1000) return;
  versionCheckedAt = Date.now();
  try {
    const page = await fetch('index.html', { cache: 'no-store' });
    const found = page.ok && (await page.text()).match(/src="app\.js(\?v=[^"]*)"/);
    if (!found || found[1] === loadedScripts) return;
    newerVersion = true;
    toast('Stigla je nova verzija aplikacije.', 'Osveži', () => location.reload());
  } catch { /* bez mreže ostaje ova verzija */ }
}

window.addEventListener('hashchange', () => newerVersion ? location.reload() : route());
document.addEventListener('visibilitychange', () => { if (!document.hidden) checkVersion(); });
window.addEventListener('pageshow', event => { if (event.persisted) checkVersion(); });
route();
// Bez mreže u prodavnici stranica se ipak otvori (sw.js).
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
setTimeout(checkVersion, 3000);
refreshMarket().then(changed => { if (changed) route(); });
