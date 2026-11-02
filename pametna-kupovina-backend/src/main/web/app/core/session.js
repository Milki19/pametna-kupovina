// Sesija uređaja (ključ se jednom menja za token, a token se obnavlja) i
// prijava greške serveru.

import { clientToken, saved } from './storage.js';

const VERSION = '2.0';

/**
 * Greška koju kupac vidi samo kao poruku (sesija, brisanje spiska) ide i na
 * /admin, kao pad u aplikaciji: bez ključa, tokena i sadržaja spiska. Jednom
 * po vrsti dok je stranica otvorena.
 */
const reported = new Set();
function reportProblem(where, error) {
  if (reported.has(where)) return;
  reported.add(where);
  fetch('/api/v1/crash-reports', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      appVersion: 'web ' + VERSION,
      androidVersion: 'web',
      device: webDeviceName(),
      stackTrace: `web ${where}: HTTP ${error.status ?? '-'} ${error.message}\n${navigator.userAgent}`
    })
  }).catch(() => { /* bez veze nema ni izveštaja */ });
}

// Jedna obnova u isto vreme: token za obnovu važi jednom, pa bi dve paralelne
// serveru izgledale kao krađa.
let renewing = null;

async function sessionCall(path, body) {
  const response = await fetch('/api/v1/' + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body)
  });
  if (!response.ok) {
    const error = new Error('Sesija nije otvorena.');
    error.status = response.status;
    throw error;
  }
  const session = await response.json();
  saved.set('session', {
    access: session.accessToken,
    expiresAt: Date.now() + session.accessExpiresInSeconds * 1000,
    refresh: session.refreshToken,
    deviceId: session.deviceId
  });
  return session.accessToken;
}

// Safari se pominje samo na iPhone-u i iPad-u (iPad se predstavlja kao Mac sa ekranom na dodir).
const appleMobile = /iPhone|iPad|iPod/.test(navigator.userAgent)
  || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1);
const browserName = appleMobile ? 'Safari' : 'Pregledač';
const locationDenied = () => appleMobile
  ? 'Safari nema dozvolu za lokaciju. Dozvoli je za ovaj sajt (Podešavanja → Aplikacije → Safari → Lokacija) ili upiši adresu.'
  : 'Pregledač nema dozvolu za lokaciju. Dozvoli je u podešavanjima sajta (ikonica pored adrese) ili upiši adresu.';

/** Da se u spisku telefona na nalogu vidi koji je koji. */
function webDeviceName() {
  const ua = navigator.userAgent;
  const kind = /iPhone/.test(ua) ? 'iPhone' : /iPad/.test(ua) ? 'iPad' : /Android/.test(ua) ? 'Android'
    : /Macintosh/.test(ua) ? 'Mac' : /Windows/.test(ua) ? 'Windows' : null;
  return kind ? `${kind} (web)` : 'Web';
}

async function renewSession() {
  const refresh = saved.get('session')?.refresh;
  if (refresh) {
    try {
      return await sessionCall('sessions/refresh', { refreshToken: refresh });
    } catch (error) {
      if (error.status !== 401) throw error;
    }
  }
  // Server ključ nikad ne odbija (uklonjen telefon uz isti ključ dobija nov
  // nalog), pa 401 ovde dolazi sa puta: Wi-Fi sa prijavom, proxy, stara
  // kopija stranice. Ključ zato ostaje, a sledeći pokušaj vraća isti nalog.
  try {
    return await sessionCall('sessions', { deviceToken: clientToken(), deviceName: webDeviceName() });
  } catch (error) {
    if (error.status) reportProblem('sessions', error);
    throw error;
  }
}

const freshAccess = () => {
  const session = saved.get('session');
  return session?.access && session.expiresAt - 30000 > Date.now() ? session.access : null;
};

function accessToken() {
  const access = freshAccess();
  if (access) return Promise.resolve(access);
  // Druga kartica istog pregledača deli isti token za obnovu. Kad bi obe
  // obnovile u isto vreme, server bi drugu video kao krađu i ugasio sesiju, a
  // pregledač bi otvorio nov prazan nalog. Zato jedna po jedna, a ona koja
  // čeka uzima sesiju koju je prva već obnovila.
  const renew = () => freshAccess() || renewSession();
  if (!renewing) {
    renewing = (navigator.locks ? navigator.locks.request('pk-session', renew) : renew())
      .finally(() => { renewing = null; });
  }
  return renewing;
}

export { accessToken, appleMobile, browserName, locationDenied, reportProblem, VERSION };
