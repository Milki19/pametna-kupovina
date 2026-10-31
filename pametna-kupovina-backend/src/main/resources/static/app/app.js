'use strict';
// Web verzija za telefone bez Android aplikacije (iPhone). Isti server i isti
// nalog po uređaju kao Android: pregledač pamti nasumičan ključ, jednom ga
// menja za sesiju i dalje šalje samo kratak pristupni token. Napredak kupovine
// ostaje u pregledaču, kao u aplikaciji.

const VERSION = '2.0';
const view = document.getElementById('view');
const actionBar = document.getElementById('action');

// ---------- Čuvanje u pregledaču ----------

const saved = {
  get(key, fallback = null) {
    try {
      const value = localStorage.getItem('pk.' + key);
      return value == null ? fallback : JSON.parse(value);
    } catch { return fallback; }
  },
  set(key, value) {
    try {
      if (value == null) localStorage.removeItem('pk.' + key);
      else localStorage.setItem('pk.' + key, JSON.stringify(value));
    } catch { /* privatni prozor: radi i bez pamćenja */ }
  }
};

function clientToken() {
  let token = saved.get('token');
  if (!token) {
    token = crypto.randomUUID ? crypto.randomUUID()
      : [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('');
    saved.set('token', token);
  }
  return token;
}

// ---------- Sesija ----------

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
  try {
    return await sessionCall('sessions', { deviceToken: clientToken(), deviceName: webDeviceName() });
  } catch (error) {
    if (error.status !== 401) throw error;
    // Ključ ovog pregledača više ne otvara nalog: počinje se kao nov uređaj.
    saved.set('token', null);
    saved.set('session', null);
    if (saved.get('listId')) forgetLostList();
    return sessionCall('sessions', { deviceToken: clientToken(), deviceName: webDeviceName() });
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

// ---------- Server ----------

// Svaka izmena spiska podiže verziju. Provera proizvoda i preporuke se
// pamte za verziju za koju su izračunate, a najduže deset minuta, jer spisak
// može da se menja i u aplikaciji.
let listVersion = 0;
const REMEMBERED_FOR = 10 * 60 * 1000;
const remembered = (entry, key) => entry && entry.key === key && Date.now() - entry.at < REMEMBERED_FOR;

async function api(method, path, body, retried = false, signal = undefined) {
  if (method !== 'GET' && path.startsWith('shopping-lists')) listVersion++;
  let response;
  let token;
  try {
    token = await accessToken();
    response = await fetch('/api/v1/' + path, {
      method,
      headers: {
        'Authorization': 'Bearer ' + token,
        // FormData (fajl računa) sam postavlja svoj Content-Type.
        ...(body === undefined || body instanceof FormData ? {} : { 'Content-Type': 'application/json' })
      },
      body: body === undefined || body instanceof FormData ? body : JSON.stringify(body),
      signal
    });
  } catch (error) {
    // Otkazana pretraga (stiglo je novo slovo) nije greška veze.
    if (error.name === 'AbortError') throw error;
    throw new Error('Nema veze sa serverom. Proveri internet i probaj ponovo.');
  }
  if (response.status === 401 && !retried
      && (response.headers.get('WWW-Authenticate') || '').startsWith('Bearer')) {
    const session = saved.get('session');
    if (session?.access === token) saved.set('session', { ...session, expiresAt: 0 });
    return api(method, path, body, true, signal);
  }
  if (!response.ok) {
    let message = null;
    let field = null;
    try { ({ message, field } = await response.json()); } catch { /* nije JSON */ }
    const error = new Error(message || (response.status === 429
      ? 'Previše zahteva odjednom. Sačekaj minut pa probaj ponovo.'
      : 'Server trenutno ne odgovara. Probaj ponovo.'));
    error.status = response.status;
    if (field) error.field = field;
    throw error;
  }
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

/**
 * Jedan spisak po uređaju, kao u aplikaciji; nov ako je stari nestao. Dva
 * istovremena poziva (prvo otvaranje pa osvežen izgled tržišta) dele isti
 * odgovor, inače bi prazan pregledač napravio dva spiska i stavke bi završile
 * u onom koji se posle više ne otvara.
 */
let listLoading = null;
function loadList() {
  if (!listLoading) listLoading = loadListOnce().finally(() => { listLoading = null; });
  return listLoading;
}

/**
 * Spisak koji je ovaj pregledač imao više ne postoji (nalog obrisan ili
 * sesija ugašena). Kupovina po njemu ide sa njim, a kupac to saznaje na
 * spisku, umesto da zatekne prazan spisak bez reči.
 */
function forgetLostList() {
  saved.set('listId', null);
  saved.set('purchase', null);
  saved.set('listLost', true);
}

async function loadListOnce() {
  await flushDelete();
  const id = saved.get('listId');
  if (id) {
    try {
      return await api('GET', `shopping-lists/${id}`);
    } catch (error) {
      if (error.status !== 404) throw error;
      forgetLostList();
    }
  }
  const created = await api('POST', 'shopping-lists', { name: 'Moja kupovina' });
  saved.set('listId', created.id);
  return api('GET', `shopping-lists/${created.id}`);
}

// ---------- Oblik brojeva i datuma (1.086,92 RSD, 24.09.2026.) ----------

// Tržište naloga: valuta i znaci za hiljade i decimale. Srbija dok server ne
// kaže drugačije, i poslednje poznato kad nema veze.
const DEFAULT_MARKET = { currency: 'RSD', currencyMinorUnits: 2, locale: 'sr-Latn-RS', timeZone: 'Europe/Belgrade' };
let market = saved.get('market', DEFAULT_MARKET);
let separators = separatorsOf(market.locale);

function separatorsOf(locale) {
  try {
    const parts = new Intl.NumberFormat(locale).formatToParts(12345.6);
    return {
      group: parts.find(part => part.type === 'group')?.value ?? '.',
      decimal: parts.find(part => part.type === 'decimal')?.value ?? ','
    };
  } catch { return { group: '.', decimal: ',' }; }
}

async function refreshMarket() {
  try {
    const account = await api('GET', 'accounts/me');
    if (!account?.market || JSON.stringify(account.market) === JSON.stringify(market)) return false;
    market = account.market;
    separators = separatorsOf(market.locale);
    saved.set('market', market);
    return true;
  } catch { return false; }
}

function number(value, decimals) {
  const [whole, part] = Math.abs(value).toFixed(decimals).split('.');
  return (value < 0 ? '−' : '') + whole.replace(/\B(?=(\d{3})+(?!\d))/g, separators.group)
    + (part ? separators.decimal + part : '');
}
const money = value => number(value, market.currencyMinorUnits) + ' ' + market.currency;
const whole = value => number(Math.round(value), 0);
function decimal(value, max = 2) {
  const text = number(value, max);
  return text.includes(separators.decimal)
    ? text.replace(/0+$/, '').replace(new RegExp('\\' + separators.decimal + '$'), '')
    : text;
}
function date(iso) {
  const [y, m, d] = String(iso).slice(0, 10).split('-');
  return `${d}.${m}.${y}.`;
}
const shortDate = iso => date(iso).slice(0, 6);
// Cena starija od tri dana dobija datum uz stavku; svežu ne treba naglašavati.
function stalePrice(priceDate, requestedDate) {
  if (!priceDate || !requestedDate) return false;
  const day = 24 * 60 * 60 * 1000;
  return Date.parse(requestedDate.slice(0, 10)) - Date.parse(priceDate.slice(0, 10)) > 3 * day;
}
function plural(count, one, few, many) {
  const n10 = count % 10, n100 = count % 100;
  if (n10 === 1 && n100 !== 11) return one;
  if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return few;
  return many;
}
const counted = (count, one, few, many) => `${count} ${plural(count, one, few, many)}`;
function amountLabel(value, unit, packageCount = 1) {
  if (packageCount > 1) return `${packageCount} × ${amountLabel(value / packageCount, unit)}`;
  const large = (unit === 'g' || unit === 'ml') && value >= 1000;
  const shown = decimal(large ? value / 1000 : value, 3);
  const label = unit === 'g' ? (large ? 'kg' : 'g') : unit === 'ml' ? (large ? 'l' : 'ml') : unit === 'piece' ? 'kom' : (unit || '?');
  return `${shown} ${label}`;
}
function distance(km) {
  return km < 1 ? `${Math.round(km * 100) * 10} m` : `${decimal(km, 1)} km`;
}
function duration(seconds) {
  // Kratak put je „1 min“, ne „0 min“.
  const minutes = seconds > 0 ? Math.max(1, Math.floor((seconds + 30) / 60)) : 0;
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}
const perUnit = unit => unit === 'g' ? 'kg' : unit === 'ml' ? 'l' : 'kom';

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);
const pill = (text, tone = '') => `<span class="pill ${tone}">${esc(text)}</span>`;
const notice = (text, tone = '') => `<div class="notice ${tone}" ${tone === 'err' ? 'role="alert"' : ''}>${esc(text)}</div>`;
const spinner = text => `<div class="spinner"></div><p class="center muted">${esc(text)}</p>`;
// Ikonice su ukras; ime dugmeta nosi tekst ili aria-label.
const icon = {
  add: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  paste: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M19 2h-4.18C14.4.84 13.3 0 12 0S9.6.84 9.18 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm7 18H5V4h2v3h10V4h2v16z"/></svg>',
  trash: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
  pin: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3A8.99 8.99 0 0 0 13 3.06V1h-2v2.06A8.99 8.99 0 0 0 3.06 11H1v2h2.06A8.99 8.99 0 0 0 11 20.94V23h2v-2.06A8.99 8.99 0 0 0 20.94 13H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"/></svg>',
  route: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M21.71 11.29l-9-9a1 1 0 0 0-1.42 0l-9 9a1 1 0 0 0 0 1.42l9 9a1 1 0 0 0 1.42 0l9-9a1 1 0 0 0 0-1.42zM14 14.5V12h-4v3H8v-4c0-.55.45-1 1-1h5V7.5l3.5 3.5-3.5 3.5z"/></svg>',
  card: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M20 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6c0-1.11-.89-2-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z"/></svg>',
  tag: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z"/></svg>',
  more: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>',
  chevron: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg>'
};

// ---------- Akcije: popust, boja i red ispod cene ----------

/** Ceo procenat popusta, kao na serveru; null kad nema niže cene. */
function discountPercent(regular, price) {
  if (regular == null || price == null || regular <= 0 || price >= regular) return null;
  const percent = Math.round((1 - price / regular) * 100);
  return percent >= 1 ? percent : null;
}
// Iste boje kao Android: nana do 19 %, ćilibar do 39 %, crvena od 40 %.
const tier = percent => percent >= 40 ? 't-l' : percent >= 20 ? 't-m' : 't-s';
const saleBadge = (percent, large = false) =>
  `<span class="sale-badge ${tier(percent)} ${large ? 'large' : ''}" aria-label="Popust ${percent} odsto">−${percent}%</span>`;
/** Popust, precrtana redovna cena i do kada važi; sa krupnim slovima prelazi u novi red. */
const saleLine = (percent, regular, end) => `<div class="sale-line">${saleBadge(percent)}
  ${regular != null ? `<span class="strike small" aria-label="Redovna cena ${money(regular)}">${money(regular)}</span>` : ''}
  ${end ? `<span class="muted small">do ${shortDate(end)}</span>` : ''}</div>`;
const salesShortcut = () => `<a class="card row" href="#/akcije" style="text-decoration:none;color:inherit">
  <span class="letter t-m" style="border-radius:50%;background:var(--tier-bg);color:var(--tier-ink)">${icon.tag}</span>
  <span class="grow"><span class="name" style="display:block">Akcije</span>
  <span class="muted small">Šta je danas na popustu u radnjama blizu tebe</span></span>${icon.chevron}</a>`;

// ---------- Ekran, traka, obaveštenje ----------

let enterPending = null;
let enterTimer = null;

function screen({ title, subtitle = null, back = null, html, action = '' }) {
  document.getElementById('title').textContent = title;
  const sub = document.getElementById('subtitle');
  sub.hidden = !subtitle;
  sub.textContent = subtitle || '';
  const backButton = document.getElementById('back');
  backButton.hidden = !back;
  backButton.onclick = back ? () => { location.hash = back; } : null;
  document.body.classList.toggle('flow', Boolean(back));
  view.innerHTML = html;
  actionBar.hidden = !action;
  actionBar.innerHTML = action ? `<div>${action}</div>` : '';
  // Ulazak na ekran se animira jednom, kad stigne pravi sadržaj, a ne na
  // svako ponovno iscrtavanje (kucanje, štikliranje).
  if (enterPending && !view.querySelector('.spinner')) {
    const motion = enterPending;
    enterPending = null;
    view.classList.remove('enter', 'from-r', 'from-l');
    actionBar.classList.remove('enter');
    void view.offsetWidth;
    view.classList.add('enter');
    if (motion !== 'enter') view.classList.add(motion);
    if (action) actionBar.classList.add('enter');
    clearTimeout(enterTimer);
    enterTimer = setTimeout(() => { view.classList.remove('enter', 'from-r', 'from-l'); actionBar.classList.remove('enter'); }, 700);
  }
  document.title = `${title} · Pametna kupovina`;
}

let toastTimer = null;
/** Kratko obaveštenje; greška se čita odmah (role="alert") i ostaje duže. */
function toast(text, undoLabel = null, onUndo = null, error = false) {
  const box = document.getElementById('toast');
  box.setAttribute('role', error ? 'alert' : 'status');
  box.innerHTML = `<span>${esc(text)}</span>` + (undoLabel ? `<button type="button">${esc(undoLabel)}</button>` : '');
  box.hidden = false;
  if (onUndo) box.querySelector('button').onclick = () => { box.hidden = true; onUndo(); };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.hidden = true; }, error ? 8000 : 5000);
}

/**
 * Greška iz forme ostaje u formi, uz polje na koje se odnosi (error.field je
 * ime polja), dok se forma ne pošalje ponovo. Čitač ekrana je čita odmah.
 */
function formError(form, error) {
  clearFormError(form);
  const field = error.field && form.elements.namedItem(error.field);
  const box = document.createElement('p');
  box.className = 'field-error';
  box.id = 'err-' + (form.dataset.form || 'form');
  box.setAttribute('role', 'alert');
  box.textContent = error.message;
  if (field && field.nodeType === 1) {
    field.setAttribute('aria-invalid', 'true');
    field.setAttribute('aria-describedby', box.id);
    const row = field.closest('.row');
    (row && form.contains(row) ? row : field).after(box);
    field.focus();
  } else {
    (form.querySelector('.btn-row') || form.lastElementChild).before(box);
  }
}
function clearFormError(form) {
  form.querySelectorAll('.field-error').forEach(el => el.remove());
  form.querySelectorAll('[aria-invalid]').forEach(el => { el.removeAttribute('aria-invalid'); el.removeAttribute('aria-describedby'); });
}
/** Greška vezana za jedno polje forme. */
function fieldError(field, message) {
  const error = new Error(message);
  error.field = field;
  return error;
}

function failed(title, error, retryHash) {
  screen({
    title,
    back: retryHash === '#/spisak' ? null : '#/spisak',
    html: notice(error.message, 'err') +
      `<button class="btn" data-act="retry">Pokušaj ponovo</button>`
  });
}

// ---------- Spisak ----------

function itemAmount(item) {
  const c = item.flexibleConstraints;
  return c && c.targetQuantity ? amountLabel(c.targetQuantity * item.quantity, c.requiredBaseUnit) : `${decimal(item.quantity)} kom`;
}

function itemRule(item) {
  if (item.matchingRule === 'EXACT_PRODUCT') return item.matchedCanonicalProductId || item.barcode ? 'Tačan barkod' : null;
  if (item.matchingRule === 'PRODUCT_FAMILY') return 'Isti proizvod, sve varijante';
  const c = item.flexibleConstraints || {};
  const parts = [];
  if (c.category && c.category.trim().toLowerCase() !== item.name.trim().toLowerCase()) parts.push('kategorija ' + c.category);
  if (c.requiredBrand) parts.push('brend ' + c.requiredBrand);
  const text = parts.join(', ');
  return text ? text[0].toUpperCase() + text.slice(1) : null;
}

function itemAttention(item) {
  if (item.matchingStatus === 'NEEDS_CONFIRMATION') return pill('Treba tvoja potvrda', 'warn');
  if (item.matchingStatus === 'UNMATCHED') return pill('Nije pronađeno', 'err');
  return '';
}

let listState = { list: null, adding: null, editing: null, rejected: null };

// Iste granice kao na serveru: količina 0,01–999, naziv koji se kuca do 80 znakova.
const MIN_QUANTITY = 0.01;
const MAX_QUANTITY = 999;
const MAX_NAME = 80;
const quantityProblem = 'Količina mora biti od 0,01 do 999.';

// Jedinice kao u Android uređivaču: kilogram mesa, ali 400 grama sira.
const editUnits = { kg: ['g', 1000], g: ['g', 1], l: ['ml', 1000], ml: ['ml', 1], kom: ['piece', 1] };
function storedUnit(baseUnit, amount) {
  if (baseUnit === 'g') return amount != null && amount < 1000 ? 'g' : 'kg';
  if (baseUnit === 'ml') return amount != null && amount < 1000 ? 'ml' : 'l';
  return baseUnit === 'piece' ? 'kom' : null;
}
const inputNumber = value => decimal(value, 3).split(separators.group).join('');
const parseNumber = text => {
  const value = Number(String(text ?? '').trim().replace(/\s/g, '').replace(',', '.'));
  return String(text ?? '').trim() && Number.isFinite(value) ? value : null;
};

/** Izmena na mestu: „bilo koji" menja naziv, količinu i brend; tačan proizvod samo broj pakovanja. */
function itemEditor(item) {
  const flexible = item.matchingRule === 'FLEXIBLE_CATEGORY';
  const c = item.flexibleConstraints || {};
  // Bez upisane količine („mleko, 1 kom“) nudi se komad, ne kilogram.
  const unit = storedUnit(c.requiredBaseUnit, c.targetQuantity) || 'kom';
  const amount = c.targetQuantity ? inputNumber(c.targetQuantity / editUnits[unit][1]) : '';
  return `<form class="card" data-form="edit-item" data-id="${item.id}">
    <div class="label accent">Izmeni stavku</div>
    ${flexible ? `
      <label class="label" for="edit-name">Šta kupuješ</label>
      <input id="edit-name" name="name" type="text" value="${esc(item.name)}" autocomplete="off" maxlength="${MAX_NAME}" required>
      <label class="label" for="edit-amount">Koliko ti ukupno treba (opciono)</label>
      <div class="row"><input id="edit-amount" name="amount" inputmode="decimal" value="${esc(amount)}" placeholder="npr. 1,5" style="flex:1">
        <select name="unit" aria-label="Jedinica" style="width:96px">${Object.keys(editUnits).map(u =>
          `<option value="${u}" ${u === unit ? 'selected' : ''}>${u}</option>`).join('')}</select></div>
      <p class="muted small">Biramo cela pakovanja, sa najviše 25% viška. Bez količine kupuješ onoliko pakovanja koliko upišeš ispod.</p>
      <label class="label" for="edit-brand">Samo ovaj brend (opciono)</label>
      <input id="edit-brand" name="brand" type="text" value="${esc(c.requiredBrand || '')}" autocomplete="off">`
      : `<div class="name">${esc(item.name)}</div>`}
    <label class="label" for="edit-quantity">${flexible ? 'Puta' : 'Broj pakovanja'}</label>
    <input id="edit-quantity" name="quantity" inputmode="decimal" value="${esc(inputNumber(item.quantity))}" required>
    <div class="btn-row"><button type="button" class="btn" data-act="cancel-edit">Odustani</button><button class="btn primary">Sačuvaj</button></div>
  </form>`;
}

async function saveItem(id, data) {
  const listId = listState.list.id;
  const item = listState.list.items.find(i => i.id === id);
  const quantity = parseNumber(data.get('quantity'));
  if (!(quantity >= MIN_QUANTITY && quantity <= MAX_QUANTITY)) throw fieldError('quantity', quantityProblem);
  const flexible = item.matchingRule === 'FLEXIBLE_CATEGORY';
  let name = item.name;
  let constraints = item.flexibleConstraints;
  if (flexible) {
    name = String(data.get('name') || '').trim();
    if (!name) throw fieldError('name', 'Upiši šta kupuješ.');
    if (name.length > MAX_NAME) throw fieldError('name', `Naziv može imati najviše ${MAX_NAME} znakova.`);
    const c = item.flexibleConstraints || {};
    const amountText = String(data.get('amount') || '').trim();
    const amount = parseNumber(amountText);
    if (amountText && !(amount > 0)) throw fieldError('amount', 'Količina nije ispravna. Primer: 1,5.');
    const [baseUnit, factor] = editUnits[data.get('unit')] || editUnits.kg;
    // Kategorija prati naziv kad je bila isto što i naziv, kao u aplikaciji.
    const sameCategory = !c.category || c.category.trim().toLowerCase() === item.name.trim().toLowerCase();
    constraints = {
      ...c,
      category: sameCategory ? name : c.category,
      requiredBrand: String(data.get('brand') || '').trim() || null,
      targetQuantity: amount ? amount * factor : null,
      requiredBaseUnit: amount ? baseUnit : c.minPackageQuantity || c.maxPackageQuantity ? c.requiredBaseUnit : null,
      // Granice pakovanja važe u jedinici količine; druga jedinica ih poništava.
      ...(amount && c.requiredBaseUnit && c.requiredBaseUnit !== baseUnit ? { minPackageQuantity: null, maxPackageQuantity: null } : {})
    };
  }
  await api('PUT', `shopping-lists/${listId}/items/${id}`, {
    name,
    rawInput: name === item.name ? item.rawInput : name,
    barcode: item.barcode,
    canonicalProductId: item.matchingRule === 'EXACT_PRODUCT' ? item.matchedCanonicalProductId : null,
    productFamilyId: item.matchingRule === 'PRODUCT_FAMILY' ? item.matchedProductFamilyId : null,
    quantity,
    matchingRule: item.matchingRule,
    flexibleConstraints: flexible ? constraints : item.flexibleConstraints
  });
  listState.editing = null;
  listState.list = await api('GET', `shopping-lists/${listId}`);
  renderList();
  toast('Sačuvano.');
}

async function showList() {
  screen({ title: 'Moj spisak', html: spinner('Učitavam spisak…') });
  listState.editing = null;
  try {
    listState.list = await loadList();
  } catch (error) {
    return failed('Moj spisak', error, '#/spisak');
  }
  renderList();
}

/** Ista stavka dva puta: upozorenje, a kad se količine mogu sabrati i dugme koje ih spaja. */
const sameName = name => String(name || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
  .replace(/đ/gi, 'dj').toLowerCase().replace(/\s+/g, ' ').trim();
const duplicateGroups = items => {
  const seen = new Map();
  items.forEach(item => seen.set(sameName(item.name), [...(seen.get(sameName(item.name)) || []), item]));
  return [...seen.values()].filter(group => group.length > 1);
};

/**
 * Prva stavka ostaje (mesto, pravilo, proizvod) i dobija zbir količina.
 * Null kad zbir nema smisla: drugo pravilo, proizvod ili brend, ili
 * količina u različitim jedinicama („mleko 1 l“ i „mleko“ od 1 kom).
 */
function mergePlan(group) {
  const [keep] = group;
  const c = keep.flexibleConstraints || {};
  const same = (item, pick) => sameName(pick(item) ?? '') === sameName(pick(keep) ?? '');
  const fits = group.every(item => item.matchingRule === keep.matchingRule
    // Server upisuje svoj pogodak i u „bilo koji“, pa se proizvod poredi samo kad ga pravilo traži.
    && (keep.matchingRule !== 'EXACT_PRODUCT' || (item.matchedCanonicalProductId === keep.matchedCanonicalProductId
      && same(item, i => i.barcode)))
    && (keep.matchingRule !== 'PRODUCT_FAMILY' || item.matchedProductFamilyId === keep.matchedProductFamilyId)
    && same(item, i => i.flexibleConstraints?.requiredBrand)
    && same(item, i => i.flexibleConstraints?.category));
  if (!fits) return null;
  const targets = group.map(item => item.flexibleConstraints?.targetQuantity || null);
  if (targets.every(t => t == null)) {
    const quantity = Math.round(group.reduce((sum, item) => sum + item.quantity, 0) * 100) / 100;
    return quantity <= MAX_QUANTITY ? { keep, quantity, constraints: keep.flexibleConstraints } : null;
  }
  if (!targets.every(t => t != null)
      || !group.every(item => item.flexibleConstraints.requiredBaseUnit === c.requiredBaseUnit)) return null;
  // Isto kao itemAmount: ukupno je ciljna količina puta broj.
  const target = group.reduce((sum, item) => sum + item.flexibleConstraints.targetQuantity * item.quantity, 0);
  if (target > (c.requiredBaseUnit === 'piece' ? MAX_QUANTITY : MAX_QUANTITY * 1000)) return null;
  return { keep, quantity: 1, constraints: { ...c, targetQuantity: Math.round(target * 1000) / 1000 } };
}

function duplicateNotice(items) {
  const groups = duplicateGroups(items);
  if (!groups.length) return '';
  const mergeable = groups.filter(mergePlan);
  const text = groups.map(group => `„${group[0].name}“ je na spisku ${group.length === 2 ? 'dva puta' : group.length + ' puta'}.`).join(' ')
    + (mergeable.length === groups.length ? ' Spoji ih u jednu stavku ili obriši višak.'
      : mergeable.length ? ' Gde se količine mogu sabrati, spoji ih; ostalo obriši ako je greška.'
        : ' Razlikuju se po proizvodu, brendu ili jedinici, pa ih ne spajam. Ako je to greška, obriši višak.');
  if (!mergeable.length) return notice(text, 'warn');
  return `<div class="card">${notice(text, 'warn')}<div class="btn-row">${mergeable.map(group =>
    `<button class="btn tonal" data-act="merge" data-id="${group[0].id}">Spoji „${esc(group[0].name)}“</button>`).join('')}</div></div>`;
}

/** Prva stavka dobija zbir, ostale se brišu; spisak se zatim čita sa servera. */
async function mergeDuplicates(id) {
  await flushDelete();
  const listId = listState.list.id;
  const group = duplicateGroups(listState.list.items).find(g => g[0].id === id);
  const plan = group && mergePlan(group);
  if (!plan) return renderList();
  const { keep, quantity, constraints } = plan;
  await api('PUT', `shopping-lists/${listId}/items/${keep.id}`, {
    ...itemRequest(keep), quantity, flexibleConstraints: constraints
  });
  for (const extra of group.slice(1)) {
    await api('DELETE', `shopping-lists/${listId}/items/${extra.id}`)
      .catch(error => { if (error.status !== 404) throw error; });
  }
  listState.editing = null;
  listState.list = await api('GET', `shopping-lists/${listId}`);
  renderList();
  const merged = listState.list.items.find(i => i.id === keep.id);
  toast(merged ? `Spojeno: ${merged.name}, ${itemAmount(merged)}.` : 'Spojeno.');
}

/** Stavka kakva jeste, u obliku koji server prima za dodavanje i izmenu. */
const itemRequest = item => ({
  name: item.name,
  rawInput: item.rawInput,
  barcode: item.barcode,
  canonicalProductId: item.matchingRule === 'EXACT_PRODUCT' ? item.matchedCanonicalProductId : null,
  productFamilyId: item.matchingRule === 'PRODUCT_FAMILY' ? item.matchedProductFamilyId : null,
  quantity: item.quantity,
  matchingRule: item.matchingRule,
  flexibleConstraints: item.flexibleConstraints
});

function lostListNotice() {
  if (!saved.get('listLost')) return '';
  // Spisak se vratio (ili je već popunjen), pa poruka o praznom više ne važi.
  if (listState.list.items.length) {
    saved.set('listLost', null);
    return '';
  }
  const signedIn = typeof account !== 'undefined' && account.state?.signedIn;
  return `<div class="card">${notice('Tvoj prethodni spisak više nije dostupan, pa je ovo nov, prazan spisak.'
      + (signedIn ? '' : ' Da se to ne ponovi, prijavi se preko Google-a: spisak se tada čuva uz nalog.'), 'warn')}
    <div class="btn-row">
      <button class="btn" data-act="lost-list-ok">U redu</button>
      ${signedIn ? '' : '<a class="btn primary" href="#/vise">Prijavi se</a>'}
    </div></div>`;
}

/** Posle završene kupovine: spisak može da ostane ili da se počne ispočetka. */
function purchaseDoneNotice(items) {
  if (!saved.get('purchaseDone')) return '';
  if (!items.length) { saved.set('purchaseDone', null); return ''; }
  return `<div class="card">${notice('Kupovina je završena. Stavke su i dalje na spisku.', 'pos')}
    <div class="btn-row">
      <button class="btn" data-act="keep-list">Zadrži spisak</button>
      <button class="btn primary" data-act="start-new-list">Započni nov spisak</button>
    </div></div>`;
}

/** Sve stavke odjednom; spisak ostaje isti, pa ga ukućani i aplikacija i dalje vide. */
async function clearList() {
  await flushDelete();
  const listId = listState.list.id;
  await api('DELETE', `shopping-lists/${listId}/items`);
  saved.set('purchaseDone', null);
  const purchase = saved.get('purchase');
  if (purchase?.listId === listId) saved.set('purchase', null);
  listState.editing = null;
  listState.list = await api('GET', `shopping-lists/${listId}`);
  renderList();
}

function renderList() {
  const items = listState.list.items;
  let purchase = saved.get('purchase');
  // Kupovina po spisku koji ovaj pregledač više nema (stari, ili drugi u domaćinstvu).
  if (purchase?.listId != null && purchase.listId !== listState.list.id) {
    saved.set('purchase', null);
    purchase = null;
  }
  const form = listState.adding === 'one'
    ? `<form class="card" data-form="add-one">
         <label class="label" for="one">Šta kupuješ</label>
         <input id="one" name="text" type="text" autocomplete="off" enterkeyhint="done" placeholder="npr. mleko 2l ili jaja 10 kom" value="${esc(listState.rejected?.text || '')}" ${rejectedMark('one')}required>${rejectedNote()}
         <div class="btn-row"><button type="button" class="btn" data-act="cancel-add">Odustani</button><button class="btn primary">Dodaj</button></div>
       </form>`
    : listState.adding === 'paste'
      ? `<form class="card" data-form="add-many">
           <label class="label" for="many">Nalepi spisak iz poruke ili beleške</label>
           <textarea id="many" name="text" placeholder="mleko&#10;hleb&#10;ćevapi 3kg&#10;2x pivo" ${rejectedMark('many')}required>${esc(listState.rejected?.text || '')}</textarea>${rejectedNote()}
           <div class="btn-row"><button type="button" class="btn" data-act="cancel-add">Odustani</button><button class="btn primary">Dodaj sve</button></div>
         </form>`
      : '';
  screen({
    title: 'Moj spisak',
    subtitle: items.length ? counted(items.length, 'stavka', 'stavke', 'stavki') : null,
    html: lostListNotice() + purchaseDoneNotice(items) +
      (purchase ? `<a class="card row" href="#/kupovina" style="text-decoration:none;color:inherit">
          <div class="grow"><div class="name">Kupovina u toku</div>
          <div class="muted small">Kupljeno ${purchaseBought(purchase)} od ${purchaseItems(purchase).length}</div></div>${icon.chevron}</a>` : '') +
      `<div class="add-bar">
         <button class="add-row" data-act="add-one">${icon.add}Dodaj stavku</button>
         <button class="btn tonal" data-act="add-many">${icon.paste}Nalepi spisak</button>
       </div>` + form + (listState.adding ? '' : salesShortcut()) +
      (items.length
        ? `<div class="section">Stavke na spisku ${pill(items.length)}</div>` + duplicateNotice(items) + items.map(item => item.id === listState.editing ? itemEditor(item) : `
          <div class="card">
            <div class="row">
              <div class="grow" data-act="edit" data-id="${item.id}" role="button" tabindex="0" style="cursor:pointer" aria-label="Izmeni ${esc(item.name)}">
                ${itemAttention(item)}
                <div class="name">${esc(item.name)}</div>
                ${item.note ? `<div class="muted small">${esc(item.note)}</div>` : ''}
                ${itemRule(item) ? `<div class="muted small">${esc(itemRule(item))}</div>` : ''}
              </div>
              <span class="amount">${esc(itemAmount(item))}</span>
              <button class="icon-action" data-act="delete" data-id="${item.id}" aria-label="Obriši ${esc(item.name)}">${icon.trash}</button>
            </div>
          </div>`).join('') +
          `<button class="btn danger" data-act="clear-list">${icon.trash}Obriši ceo spisak</button>`
        : `<div class="card soft center"><div class="title">Spisak je prazan</div>
           <p class="muted">Nalepi spisak iz poruke ili beleške, ili dodaj stavku po stavku.</p></div>`),
    action: `<button class="btn primary main" data-act="calculate" ${items.length ? '' : 'disabled'}>Izračunaj</button>`
  });
  const field = view.querySelector('[data-form] input:not([type=hidden]), [data-form] textarea');
  if (field && !listState.editing) field.focus();
  const editing = view.querySelector('[data-form=edit-item]');
  if (editing) editing.scrollIntoView({ block: 'nearest' });
}

const rejectedMark = id => listState.rejected ? `aria-invalid="true" aria-describedby="${id}-error" ` : '';

/** Redovi koje server nije dodao ostaju u polju, a razlog svakog piše ispod njega. */
function rejectedNote() {
  const lines = listState.rejected?.lines;
  if (!lines?.length) return '';
  const field = listState.adding === 'paste' ? 'many' : 'one';
  return `<p class="field-error" id="${field}-error" role="alert">${
    lines.map(line => esc(`„${line.text}“: ${line.message}`)).join('<br>')}</p>`;
}

async function addText(text) {
  const listId = listState.list.id;
  const result = await api('POST', `shopping-lists/${listId}/items/paste`, { text });
  const rejected = result.rejectedLines || [];
  listState.rejected = rejected.length ? { text: rejected.map(line => line.text).join('\n'), lines: rejected } : null;
  if (!rejected.length) listState.adding = null;
  listState.list = await api('GET', `shopping-lists/${listId}`);
  renderList();
  const added = result.createdCount === 1 ? 'Dodato.'
    : result.createdCount ? `Dodato: ${counted(result.createdCount, 'stavka', 'stavke', 'stavki')}.` : 'Nema stavki za dodavanje.';
  toast(rejected.length ? `${added} Nije dodato: ${rejected.length}, razlog je ispod polja.` : added);
}

// Brisanje se šalje serveru tek kad prođe vreme za „Vrati“, pa vraćena stavka
// zadržava svoje mesto i oznaku („Nije pronađeno“) umesto da postane nova.
let pendingDelete = null;
let deleting = Promise.resolve();

/** Šalje brisanje koje čeka; ekrani koji čitaju spisak sa servera prvo čekaju ovo. */
function flushDelete() {
  if (pendingDelete) {
    const { listId, item, timer } = pendingDelete;
    clearTimeout(timer);
    pendingDelete = null;
    deleting = api('DELETE', `shopping-lists/${listId}/items/${item.id}`)
      .catch(error => { if (error.status !== 404) toast(`Nije obrisano: ${item.name}. ${error.message}`, null, null, true); });
  }
  return deleting;
}
document.addEventListener('visibilitychange', () => { if (document.hidden) flushDelete(); });

async function deleteItem(id) {
  await flushDelete();
  const listId = listState.list.id;
  const index = listState.list.items.findIndex(i => i.id === id);
  const item = listState.list.items[index];
  listState.list.items = listState.list.items.filter(i => i.id !== id);
  renderList();
  pendingDelete = { listId, item, timer: setTimeout(flushDelete, 5000) };
  toast(`Obrisano: ${item.name}`, 'Vrati', async () => {
    if (pendingDelete?.item === item) {
      clearTimeout(pendingDelete.timer);
      pendingDelete = null;
      if (listState.list?.id === listId) {
        listState.list.items.splice(Math.min(index, listState.list.items.length), 0, item);
        renderList();
      }
      return;
    }
    await api('POST', `shopping-lists/${listId}/items`, itemRequest(item));
    listState.list = await api('GET', `shopping-lists/${listId}`);
    renderList();
  });
}

// ---------- Provera proizvoda (korak 1) ----------

let matching = null;
let matchingFor = null;

// Jedno stanje svuda (spisak, korak 1, plan): pronađeno, treba potvrda, nije pronađeno.
const isFound = item => item.matchingStatus === 'AUTO_MATCHED' || item.matchingStatus === 'CONFIRMED';
const needsDecision = item => item.blocksOptimization || item.matchingStatus === 'NEEDS_CONFIRMATION';
const notFound = item => !needsDecision(item) && !isFound(item);
const canUseAsFlexible = item => item.matchingRule === 'EXACT_PRODUCT' && item.matchingStatus === 'UNMATCHED';
const statusText = { PENDING: 'Čeka proveru', AUTO_MATCHED: 'Pronađeno', NEEDS_CONFIRMATION: 'Treba potvrda', CONFIRMED: 'Pronađeno', UNMATCHED: 'Nije pronađeno' };
const statusTone = { PENDING: 'warn', AUTO_MATCHED: 'pos', NEEDS_CONFIRMATION: 'warn', CONFIRMED: 'pos', UNMATCHED: 'err' };

// „Izaberi iz pretrage" za jednu stavku: prvih pet predloga ispod nje.
let picking = null;

async function showMatching() {
  screen({ title: 'Provera proizvoda', back: '#/spisak', html: spinner('Tražim odgovarajuće proizvode…') });
  try {
    const listId = saved.get('listId');
    if (!listId) { location.hash = '#/spisak'; return; }
    await flushDelete();
    if (!(matching && remembered(matchingFor, `${listId}|${listVersion}`))) {
      matching = await api('POST', `shopping-lists/${listId}/matching?includeProductsWithoutBarcode=true`);
      matchingFor = { key: `${listId}|${listVersion}`, at: Date.now() };
    }
  } catch (error) {
    return failed('Provera proizvoda', error, '#/provera');
  }
  if (pendingPick != null && matching.items.some(i => i.itemId === pendingPick)) {
    const itemId = pendingPick;
    pendingPick = null;
    return openPick(itemId);
  }
  pendingPick = null;
  if (picking && !matching.items.some(i => i.itemId === picking.itemId)) picking = null;
  renderMatching();
}

function pickerCard(item) {
  if (!picking || picking.itemId !== item.itemId) {
    return `<button class="btn tonal" data-act="pick-open" data-item="${item.itemId}"
      aria-label="Izaberi iz pretrage za „${esc(item.requestedName)}“">Izaberi iz pretrage</button>`;
  }
  return `<form class="card soft" data-form="pick-search" role="search">
      <label class="label" for="pick-q">Traži proizvod</label>
      <div class="row"><input id="pick-q" name="q" type="search" enterkeyhint="search" autocomplete="off"
        value="${esc(picking.query)}" style="flex:1"><button class="btn primary">Traži</button></div>
      <div id="pick-results" data-item="${item.itemId}">${pickResults(item)}</div>
      <button type="button" class="btn text" data-act="pick-cancel">Odustani</button>
    </form>`;
}

function betterPackCard(item) {
  const b = picking.better;
  if (!b) return '';
  const unit = b.chosen.baseUnit;
  return `<div class="card soft" role="status">
      <div class="name">Treba ti ${amountLabel(b.need, unit)}, a izabrano pakovanje je ${amountLabel(b.chosen.quantityValue, unit)} (od ${money(b.bigPrice)}).</div>
      <p class="small">${b.packages === 1 ? '' : `${b.packages} × `}${esc(amountLabel(b.product.quantityValue, unit))} je od ${money(b.cost)}: ${esc(b.product.name)}. Zameni?</p>
      <div class="row" style="gap:8px;flex-wrap:wrap">
        <button type="button" class="btn primary" data-act="pick-better">Zameni</button>
        <button type="button" class="btn" data-act="pick-keep">Zadrži ${amountLabel(b.chosen.quantityValue, unit)}</button>
      </div></div>`;
}

function pickResults(item) {
  if (picking.better) return betterPackCard(item);
  return `${picking.loading ? spinner('Tražim…') : ''}
      ${picking.error ? `<p class="small" role="alert" style="color:var(--error)">${esc(picking.error)}</p>` : ''}
      ${picking.results && !picking.results.length && !picking.loading ? `<p class="muted small">Nema proizvoda za „${esc(picking.query)}“. Probaj kraći naziv.</p>` : ''}
      ${(picking.results || []).map((p, index) => `
        <button type="button" class="card" style="text-align:left;cursor:pointer" data-act="pick" data-index="${index}"
          aria-label="Izaberi ${esc(p.name)} za „${esc(item.requestedName)}“">
          <div class="row"><div class="grow"><div style="overflow-wrap:anywhere">${esc(p.name)}</div>
            <div class="muted small">${esc([p.brand, p.quantityValue && p.baseUnit ? amountLabel(p.quantityValue, p.baseUnit, p.packageCount) : null,
              p.hasUsablePrice ? null : 'bez cene'].filter(Boolean).join(' · '))}</div></div>
          <b class="label accent">Izaberi</b></div>
        </button>`).join('')}`;
}

/** Dok se kuca menja se samo spisak predloga, da polje zadrži slovo i kursor. */
function renderPick() {
  const box = view.querySelector('#pick-results');
  const item = picking && matching.items.find(i => i.itemId === picking.itemId);
  if (box && item && Number(box.dataset.item) === item.itemId) box.innerHTML = pickResults(item);
  else renderMatching();
}

function decisionCard(item, showExplanation) {
  return `
    <div class="card" data-item-card="${item.itemId}">
      <div class="row top-align">
        <div class="grow"><div class="label">Sa spiska</div><div class="title" style="overflow-wrap:anywhere">„${esc(item.requestedName)}“</div></div>
        ${pill(statusText[item.matchingStatus], statusTone[item.matchingStatus])}
      </div>
      ${showExplanation ? `<p class="muted small">${esc(item.explanation)}</p>` : ''}
      ${item.matchingStatus === 'NEEDS_CONFIRMATION' ? item.candidates.slice(0, 5).map((c, ci) => `
        <button class="card soft" style="text-align:left;cursor:pointer" data-act="choose" data-item="${item.itemId}" data-candidate="${ci}"
          aria-label="Izaberi ${esc(c.name)} za „${esc(item.requestedName)}“">
          <div class="row"><div class="grow">
            <div>${esc(c.name)}</div>
            <div class="row small muted">${pill(Math.round(c.score.totalScore * 100) + '%', 'pos')}
              ${esc([c.brand, c.quantityValue && c.baseUnit ? amountLabel(c.quantityValue, c.baseUnit, c.packageCount) : null].filter(Boolean).join(' · '))}</div>
          </div><b class="label accent">Izaberi</b></div>
        </button>`).join('') : ''}
      ${canUseAsFlexible(item) ? `
        <button class="card soft" style="text-align:left;cursor:pointer" data-act="flexible" data-item="${item.itemId}"
          aria-label="Neka aplikacija izabere najpovoljnije za „${esc(item.requestedName)}“">
          <div class="name" style="color:var(--primary)">Neka aplikacija izabere najpovoljnije</div>
          <div class="muted small">Kad ti nije važan brend ni pakovanje.</div>
        </button>` : ''}
      ${pickerCard(item)}
      ${item.matchingStatus === 'NEEDS_CONFIRMATION' ? `<button class="btn text" data-act="reject" data-item="${item.itemId}" aria-label="Nijedan, ostavi „${esc(item.requestedName)}“ neupareno">Nijedan, ostavi neupareno</button>` : ''}
    </div>`;
}

function renderMatching(errorText = null) {
  const picked = picking && matching.items.find(i => i.itemId === picking.itemId && isFound(i));
  const decide = matching.items.filter(needsDecision);
  const missing = matching.items.filter(notFound);
  const settled = matching.items.filter(i => isFound(i) && i !== picked);
  const found = matching.items.filter(isFound).length;
  const share = matching.totalItems ? Math.round(found / matching.totalItems * 100) : 0;
  const cards = list => list.map((item, index) =>
    decisionCard(item, index === 0 || list[index - 1].explanation !== item.explanation)).join('');
  screen({
    title: 'Provera proizvoda',
    back: '#/spisak',
    html: `
      <div class="card soft">
        <div class="label accent">Korak 1 od 3</div>
        <div class="title">${decide.length ? `${decide.length} ${plural(decide.length, 'stavka traži', 'stavke traže', 'stavki traži')} tvoju odluku`
          : missing.length ? `${counted(missing.length, 'stavka nije pronađena', 'stavke nisu pronađene', 'stavki nije pronađeno')}` : 'Sve stavke su pronađene'}</div>
        <p class="muted small">${missing.length && !decide.length ? 'Nepronađene stavke ne ulaze u račun. Izaberi ih iz pretrage ili nastavi bez njih.'
          : 'Potvrdi nejasne stavke da bismo našli najpovoljnije cene u blizini.'}</p>
        <div class="row small"><span class="grow muted">Pronađeno ${found} od ${matching.totalItems}</span>${decide.length ? `<b class="label accent">još ${decide.length}</b>` : ''}</div>
        <div class="progress"><span style="width:${share}%"></span></div>
      </div>` +
      (errorText ? notice(errorText, 'err') : '') +
      (picked ? `<div class="section">Izaberi drugi proizvod</div>` + decisionCard(picked, false) : '') +
      (decide.length ? `<div class="section">Treba tvoja odluka ${pill(decide.length)}</div>` + cards(decide) : '') +
      (missing.length ? `<div class="section">Nije pronađeno ${pill(missing.length)}</div>` + cards(missing) : '') +
      (settled.length ? `<div class="section">Pronađeno ${pill(settled.length)}</div>
        <div class="card flush">${settled.map(item => `
          <div class="list-row row top-align"><div class="grow"><div class="name">✓ ${esc(item.requestedName)}</div>
          ${item.matchingStatus === 'AUTO_MATCHED' ? `<div class="muted small">${esc(item.explanation)}</div>` : ''}</div>
          <button class="btn text small" data-act="pick-open" data-item="${item.itemId}"
            aria-label="Izaberi drugi proizvod za „${esc(item.requestedName)}“">Promeni</button></div>`).join('')}
        </div>` : ''),
    action: `<button class="btn primary main" data-act="to-location" ${matching.readyForOptimization ? '' : 'disabled'}>${
      matching.readyForOptimization ? 'Nastavi na lokaciju'
        : 'Odluči još za ' + counted(decide.length || matching.blockingItemIds.length, 'stavku', 'stavke', 'stavki')}</button>`
  });
  const field = view.querySelector('#pick-q');
  if (field) {
    field.closest('[data-item-card]')?.scrollIntoView({ block: 'nearest' });
    if (!picking.results && !picking.loading) field.focus();
  }
}

function openPick(itemId) {
  const item = matching.items.find(i => i.itemId === itemId);
  picking = { itemId, query: item.requestedName, results: null, loading: false, error: null };
  return runPick(item.requestedName);
}

async function runPick(query) {
  const current = picking;
  const signal = pickTyping.signal();
  current.query = query.trim();
  current.error = null;
  current.better = null;
  if (!searchable(current.query)) {
    current.results = [];
    current.loading = false;
    current.error = 'Upiši bar 2 slova.';
    return renderPick();
  }
  current.loading = true;
  renderPick();
  try {
    const page = await api('GET', `products/search?query=${encodeURIComponent(current.query)}&page=0&limit=5${searchLocation()}`,
      undefined, false, signal);
    current.results = page.items.slice(0, 5);
  } catch (error) {
    if (error.name === 'AbortError') return;
    current.results = [];
    current.error = error.message;
  }
  current.loading = false;
  if (picking === current) renderPick();
}

/**
 * Koliko pakovanja izabranog proizvoda traži stavka: „jogurte (3)“ ostaju 3,
 * „piletina oko 1kg“ od 500 g su 2, a „pivo 6“ u paketu od 6 je jedan paket.
 * Do četvrtine manje je dovoljno, kao kad aplikacija sama bira.
 */
function packagesFor(item, product) {
  const c = item.flexibleConstraints;
  if (c && c.targetQuantity && product.baseUnit === c.requiredBaseUnit && product.quantityValue > 0) {
    const packages = Math.ceil(c.targetQuantity * item.quantity / product.quantityValue - 0.25);
    return Math.min(MAX_QUANTITY, Math.max(1, packages));
  }
  const inPack = product.packageCount > 1 ? product.packageCount : 1;
  return item.quantity % inPack === 0 ? item.quantity / inPack : item.quantity;
}

const lowestPrice = product => Math.min(...(product.availability || [])
  .filter(a => a.minimumEffectivePrice != null && !a.priceNeedsCheck).map(a => a.minimumEffectivePrice));

/**
 * Izabrano pakovanje je mnogo veće od potrebnog („2 kg brašna“, a izabrano
 * 5 kg): manja pakovanja iste vrste koja daju potrebnu količinu jeftinije,
 * po najnižoj ceni u lancima. Izveštaj 20.10.: 5 kg za 244,99 RSD umesto 2 × 1 kg za oko 106.
 */
async function smallerPacks(item, product) {
  const c = item.flexibleConstraints;
  const need = c && c.targetQuantity ? c.targetQuantity * item.quantity : null;
  if (!need || product.baseUnit !== c.requiredBaseUnit || !(product.quantityValue > need * 1.25)) return null;
  const bigPrice = lowestPrice(product);
  if (!Number.isFinite(bigPrice)) return null;
  let page;
  try {
    page = await api('GET', `products/search?query=${encodeURIComponent(picking.query)}&page=0&limit=20${searchLocation()}`);
  } catch { return null; } // Samo predlog: izbor ide i bez njega.
  let best = null;
  for (const p of page.items) {
    if (p.baseUnit !== product.baseUnit || !(p.quantityValue > 0) || p.quantityValue >= product.quantityValue) continue;
    if (product.categoryCode && p.categoryCode !== product.categoryCode) continue;
    const price = lowestPrice(p);
    if (!Number.isFinite(price)) continue;
    const packages = Math.max(1, Math.ceil(need / p.quantityValue - 0.25));
    if (packages > 10) continue;
    const cost = packages * price;
    if (!best || cost < best.cost) best = { product: p, packages, cost };
  }
  return best && best.cost < bigPrice * 0.9 ? { ...best, need, bigPrice } : null;
}

/** Izabrani proizvod iz pretrage postaje stavka: naziv i količina sa spiska ostaju, proizvod je tačan. */
async function pickProduct(product, keepAsChosen = false) {
  const listId = matching.listId;
  const itemId = picking.itemId;
  const list = await api('GET', `shopping-lists/${listId}`);
  const item = list.items.find(i => i.id === itemId);
  if (!item) throw new Error('Stavke više nema na spisku.');
  if (!keepAsChosen) {
    const better = await smallerPacks(item, product);
    if (better && picking && picking.itemId === itemId) {
      picking.better = { chosen: product, ...better };
      return renderPick();
    }
  }
  const quantity = packagesFor(item, product);
  await api('PUT', `shopping-lists/${listId}/items/${itemId}`, {
    name: item.name,
    rawInput: item.rawInput,
    canonicalProductId: product.productFamilyId ? null : product.canonicalProductId,
    productFamilyId: product.productFamilyId || null,
    quantity,
    matchingRule: product.productFamilyId ? 'PRODUCT_FAMILY' : 'EXACT_PRODUCT'
  });
  picking = null;
  await rematch(itemId);
  renderMatching();
  toast(quantity === 1 ? `Izabrano: ${product.name}` : `Izabrano: ${product.name}, ${decimal(quantity)} kom`);
}

// Lanci koji ne objavljuju adrese prodavnica (za upozorenje u Akcijama).
let unlocatedChains = null;
async function chainWithoutAddresses(code) {
  try {
    if (!unlocatedChains) {
      const retailers = await api('GET', 'retailers');
      unlocatedChains = new Set(retailers.filter(r => r.storeLocationsKnown === false).map(r => r.code));
    }
    return unlocatedChains.has(code);
  } catch { return false; }
}

async function resolve(itemId, candidate) {
  const listId = matching.listId;
  await api('PUT', `shopping-lists/${listId}/items/${itemId}/match`, {
    action: candidate ? 'CONFIRM' : 'REJECT',
    canonicalProductId: candidate ? candidate.canonicalProductId : null,
    productFamilyId: candidate && !candidate.canonicalProductId ? candidate.productFamilyId : null,
    note: candidate ? 'Korisnik je potvrdio kandidata u web verziji.' : 'Korisnik je izabrao opciju neupareno u web verziji.'
  });
  await rematch(itemId);
  renderMatching();
}

async function useAsFlexible(itemId) {
  // Server zadržava vrstu i količinu iz naziva, a reči koje ne razume ne odbijaju izmenu.
  await api('PUT', `shopping-lists/${matching.listId}/items/${itemId}/any-of-kind`);
  if (picking && picking.itemId === itemId) picking = null;
  await rematch(itemId);
  renderMatching();
}

/** Odluka menja jednu stavku: samo ona se proverava ponovo, ostale ostaju kakve su. */
async function rematch(itemId) {
  const result = await api('POST', `shopping-lists/${matching.listId}/items/${itemId}/matching?includeProductsWithoutBarcode=true`);
  const items = matching.items.map(i => i.itemId === itemId ? result : i);
  const count = status => items.filter(i => i.matchingStatus === status).length;
  const blockingItemIds = items.filter(i => i.blocksOptimization).map(i => i.itemId);
  matching = {
    ...matching,
    items,
    automaticallyMatchedItems: count('AUTO_MATCHED'),
    confirmedItems: count('CONFIRMED'),
    itemsNeedingConfirmation: count('NEEDS_CONFIRMATION'),
    unmatchedItems: count('UNMATCHED'),
    flexibleItems: items.filter(i => i.matchingRule === 'FLEXIBLE_CATEGORY').length,
    readyForOptimization: !blockingItemIds.length,
    blockingItemIds
  };
  matchingFor = { key: `${matching.listId}|${listVersion}`, at: matchingFor ? matchingFor.at : Date.now() };
}

// ---------- Polazna tačka (korak 2) ----------

const coordinates = (lat, lng) => `${lat.toFixed(4)}, ${lng.toFixed(4)}`;
const originLabel = origin => origin.label || coordinates(origin.lat, origin.lng);

// Adrese iz pretrage ostaju na ekranu dok se ne izabere jedna.
let places = { query: '', results: null };

function showLocation(message = null, tone = 'err') {
  const last = saved.get('origin');
  const placeField = view.querySelector('[data-form=place] input[name=q]');
  if (placeField) places.query = placeField.value;
  screen({
    title: 'Odakle krećeš',
    back: '#/provera',
    html: `
      <div class="card soft">
        <div class="label accent">Korak 2 od 3</div>
        <div class="title">Polazna tačka</div>
        <p class="muted small">Put računamo od polazne tačke do prodavnica u blizini.</p>
      </div>
      <form class="card" data-form="place" role="search">
        <label class="label" for="place">Adresa ili kraj</label>
        <div class="row"><input id="place" name="q" type="search" enterkeyhint="search" autocomplete="street-address"
          placeholder="npr. Bulevar oslobođenja 10, Novi Sad" value="${esc(places.query)}" style="flex:1">
          <button class="btn primary">Traži</button></div>
      </form>
      <div class="label">Kako ideš do prodavnica</div>
      <div class="chips" role="group" aria-label="Kako ideš do prodavnica">
        <button class="chip" data-act="travel" data-value="DRIVING" aria-pressed="${!saved.get('walking', false)}">Kolima</button>
        <button class="chip" data-act="travel" data-value="WALKING" aria-pressed="${saved.get('walking', false)}">Peške</button>
      </div>
      ${saved.get('walking', false) ? '<p class="muted small">Peške: radnje do 3 km, put ne košta gorivo, a vreme se računa hodom.</p>' : ''}
      <a class="btn text small" href="#/vreme">Promeni cenu vremena i prevoza</a>
      ${places.results ? (places.results.length ? `<div class="card flush">${places.results.map((p, index) => `
        <button class="list-row row" style="width:100%;text-align:left;cursor:pointer;background:none;border:0" data-act="use-place" data-index="${index}" aria-label="Polazna tačka: ${esc(p.name)}">
          <span style="color:var(--primary)">${icon.pin}</span><span class="grow">${esc(p.name)}</span>${icon.chevron}</button>`).join('')}</div>`
        : notice('Ne nalazimo tu adresu. Dodaj grad ili probaj sa nazivom kraja.', 'warn')) : ''}
      <button class="card row" style="text-align:left;cursor:pointer" data-act="locate">
        <span class="letter" style="border-radius:50%;background:var(--mint);color:var(--on-mint)">${icon.pin}</span>
        <span class="grow"><span class="name" style="display:block">Koristi trenutnu lokaciju</span>
        <span class="muted small">${browserName} će pitati za dozvolu</span></span>${icon.chevron}
      </button>
      ${last ? `<button class="card row" style="text-align:left;cursor:pointer" data-act="use-last">
        <span class="grow"><span class="label">Poslednja polazna tačka</span><span style="display:block">${esc(originLabel(last))}</span></span>
        <span class="btn">Koristi</span></button>` : ''}
      ${message ? notice(message, tone) : ''}
      <p class="muted small">Lokacija služi samo za ovo računanje i za redosled pretrage. Ne prati se u pozadini, a server je ne čuva. Adresu server traži preko OpenStreetMap-a i ne upisuje je.</p>`
  });
}

/** Adresa tačke („Bulevar oslobođenja 10, Novi Sad“) umesto brojeva; null kad je nema. */
async function addressOf(lat, lng) {
  try {
    const place = await api('GET', `places/reverse?latitude=${lat}&longitude=${lng}`);
    return place?.name || null;
  } catch {
    return null;
  }
}

async function useOrigin(lat, lng, label = null) {
  label = label || await addressOf(lat, lng);
  saved.set('origin', label ? { lat, lng, label } : { lat, lng });
  places = { query: '', results: null };
  location.hash = '#/preporuke';
}

function locate() {
  if (!navigator.geolocation) return showLocation('Ovaj pregledač ne daje lokaciju. Upiši adresu ili kraj.');
  showLocation('Tražim lokaciju…', '');
  navigator.geolocation.getCurrentPosition(
    position => useOrigin(position.coords.latitude, position.coords.longitude),
    error => showLocation(error.code === 1
      ? locationDenied()
      : 'Lokacija trenutno nije dostupna. Probaj ponovo ili upiši adresu.'),
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 300000 }
  );
}

// ---------- Preporuke (korak 3) ----------

let recommendation = null;
let recommendationFor = null;
// Cene se menjaju jednom dnevno, pri uvozu; sat vremena je dovoljno sveže.
const PLAN_REMEMBERED_FOR = 60 * 60 * 1000;
/** Ono od čega plan zavisi: stavke, količine i odluke, bez vremena izmene. */
const planInputs = list => JSON.stringify(list.items.map(i => [i.id, i.name, i.barcode, i.quantity, i.matchingRule,
  i.matchingStatus, i.matchedCanonicalProductId, i.matchedProductFamilyId, i.matchingDecisionId, i.flexibleConstraints]));
let selectedType = 'RECOMMENDED_BALANCE';
const scenarioTitle = { SINGLE_STORE: 'Jedna prodavnica', RECOMMENDED_BALANCE: 'Preporučeni balans', LOWEST_PRICE: 'Najniža cena' };
const scenarioShort = { SINGLE_STORE: 'Jedna stanica', RECOMMENDED_BALANCE: 'Najbolje ukupno', LOWEST_PRICE: 'Najjeftinija korpa' };
const scenarioBadge = { SINGLE_STORE: 'Najbrže i najlakše', RECOMMENDED_BALANCE: 'Preporučeno', LOWEST_PRICE: 'Najniža cena korpe' };
const unresolvedStatus = { NEEDS_CONFIRMATION: 'Treba potvrda', UNMATCHED: 'Nije pronađeno', NO_VALID_PRICE: 'Nema cene', AVAILABLE: 'Dostupno' };

/** Isti plan dva puta nije izbor: kopija plana koji je već prikazan otpada. */
function distinctScenarios(result) {
  const ids = s => s.stores.map(x => x.storeId).sort().join(',');
  const same = (a, b) => a.available === b.available && a.basketCost === b.basketCost && ids(a) === ids(b);
  const best = result.recommendedBalance;
  const single = same(result.singleStore, best) ? null : result.singleStore;
  const lowest = same(result.lowestPrice, best) || (single && same(result.lowestPrice, single)) ? null : result.lowestPrice;
  return [single, best, lowest].filter(Boolean);
}

function manySmallPacks(item) {
  const q = item.purchaseQuantity;
  return Boolean(q && q.packages >= 10 && q.baseUnit && q.baseUnit !== 'piece' && q.packages !== item.requestedQuantity);
}

function quantityLine(item) {
  const q = item.purchaseQuantity;
  const packages = q && q.packages != null ? q.packages : item.requestedQuantity;
  return [
    item.effectivePrice != null && packages !== 1 ? `${decimal(packages)} × ${money(item.effectivePrice)}` : null,
    q && q.unitPrice != null && q.baseUnit ? `${money(q.unitPrice)}/${perUnit(q.baseUnit)}` : null,
    q && q.extraAmount > 0 && q.suppliedAmount != null && q.baseUnit
      ? `dobijaš ${amountLabel(q.suppliedAmount, q.baseUnit)}, ${amountLabel(q.extraAmount, q.baseUnit)} više` : null
  ].filter(Boolean).join(' · ');
}

function scenarioWarnings(s, result) {
  const warnings = [];
  if (!s.available) return warnings;
  const missing = s.items.length - s.coveredItems - (s.onTheWay?.itemIds.length || 0);
  if (!s.complete && missing > 0) {
    const unrecognised = Math.min(s.unmatchedItems, missing);
    const unpriced = missing - unrecognised;
    if (unrecognised > 0) warnings.push(`Ne prepoznajemo ${counted(unrecognised, 'stavku', 'stavke', 'stavki')}, pa ${plural(unrecognised, 'nije', 'nisu', 'nisu')} u računu.`);
    if (unpriced > 0) warnings.push(`Plan je nepotpun: ${counted(unpriced, 'stavka nema', 'stavke nemaju', 'stavki nema')} ponudu u ovim prodavnicama.`);
  }
  const small = s.items.filter(manySmallPacks).map(i => i.requestedName);
  if (small.length) warnings.push(`Od mnogo malih pakovanja: ${small.join(', ')}. Proveri da li ti tako odgovara.`);
  if (s.dataAsOf && s.dataAsOf !== result.requestedDate) warnings.push(`Cene su iz cenovnika od ${date(s.dataAsOf)}, ne od danas. Proveri ih pre kupovine.`);
  return warnings;
}

const onTheWay = (s, item) => Boolean(s.onTheWay && s.onTheWay.itemIds.includes(item.itemId));

/** Sitna stavka zbog koje ne vredi ići u još jednu prodavnicu (izveštaj 20.10.). */
function onTheWayNotice(s) {
  const o = s.onTheWay;
  if (!o || !o.itemNames.length) return '';
  const names = o.itemNames.join(', ');
  return notice(`${names.charAt(0).toUpperCase() + names.slice(1)} kupi usput. Zbog ${o.itemNames.length === 1 ? 'te stavke' : 'tih stavki'}
    bi plan imao još jednu prodavnicu: +${whole(o.extraCost)} ${market.currency} i oko ${duration(o.extraSeconds)} više.`);
}

// Peške duže od pola sata: ponuda da plan ostane u blizini (izveštaj 20.10.).
const NEAR_ONLY_METERS = 1000;
const LONG_WALK_SECONDS = 30 * 60;
function nearOnlyOffer(s, result) {
  if (result.assumptions?.travelMode !== 'WALKING') return '';
  if (saved.get('nearOnly', false)) {
    return `<div class="card soft row"><span class="grow small">Samo radnje do ${NEAR_ONLY_METERS / 1000} km.</span>
      <button class="btn text small" data-act="near-only" data-value="off">Prikaži sve u blizini</button></div>`;
  }
  if (!s.available || s.routeDurationSeconds <= LONG_WALK_SECONDS) return '';
  return `<div class="card soft row"><span class="grow small">Ovo je oko ${duration(s.routeDurationSeconds)} hoda.</span>
    <button class="btn small" data-act="near-only" data-value="on">Samo radnje do ${NEAR_ONLY_METERS / 1000} km</button></div>`;
}

/**
 * „Najbolje ukupno" skuplje od „Jedne stanice": razlika i stavke koje samo
 * ono pokriva, da se vidi šta se dobija za tu razliku.
 */
function balanceExplanation(s, result) {
  const single = result.singleStore;
  if (s.type !== 'RECOMMENDED_BALANCE' || !s.available || !single?.available
      || s.totalCost == null || single.totalCost == null) return '';
  const more = s.totalCost - single.totalCost;
  if (Math.round(more) <= 0) return '';
  const inSingle = new Set(single.items.filter(i => i.resultStatus === 'AVAILABLE').map(i => i.itemId));
  const extra = s.items.filter(i => i.resultStatus === 'AVAILABLE' && !inSingle.has(i.itemId)).map(i => i.requestedName);
  if (!extra.length) return '';
  const names = extra.length > 4 ? `${extra.slice(0, 3).join(', ')} i još ${extra.length - 3}` : extra.join(', ');
  return notice(`+${whole(more)} ${market.currency} od Jedne stanice, ali pokriva još: ${names}.`);
}

const storeAddress = store => [store.storeName,
  store.address && !store.storeName.toLowerCase().includes(store.address.toLowerCase()) ? store.address : null,
  store.city && !store.storeName.toLowerCase().includes(store.city.toLowerCase()) ? store.city : null
].filter(Boolean).join(', ');

function mapsUrl(origin, stores, walking = false) {
  const stops = stores.map(s => `${s.latitude},${s.longitude}`).filter((v, i, all) => all.indexOf(v) === i);
  let url = 'https://www.google.com/maps/dir/?api=1';
  if (origin) url += `&origin=${origin.lat},${origin.lng}`;
  url += `&destination=${stops[stops.length - 1]}`;
  if (stops.length > 1) url += `&waypoints=${stops.slice(0, -1).join('%7C')}`;
  return url + (walking ? '&travelmode=walking' : '&travelmode=driving');
}

async function showRecommendation() {
  const origin = saved.get('origin');
  const listId = saved.get('listId');
  if (!origin || !listId) { location.hash = '#/lokacija'; return; }
  // Obrisana stavka koja još čeka „Vrati“ prvo stiže do servera.
  await flushDelete();
  const walking = saved.get('walking', false);
  const travel = (walking ? '&travelMode=WALKING' : '')
    + (walking && saved.get('nearOnly', false) ? `&radiusMeters=${NEAR_ONLY_METERS}` : '');
  try {
    // Plan se ne računa ponovo dok se spisak ne promeni. Stavke se pitaju
    // server, jer spisak može da se menja i u aplikaciji, a to je brzo.
    // Cena vremena, kilometra i stajanja može da se promeni i u aplikaciji ili kod ukućanina.
    const [list, trip] = await Promise.all([api('GET', `shopping-lists/${listId}`),
      api('GET', 'accounts/me/trip-costs').catch(() => null)]);
    const key = `${listId}|${origin.lat}|${origin.lng}|${travel}|${new Date().toDateString()}|${planInputs(list)}|${trip?.valuePerHour}|${trip?.costPerStop}|${trip?.costPerKm}`;
    if (recommendation && recommendationFor && recommendationFor.key === key
        && Date.now() - recommendationFor.at < PLAN_REMEMBERED_FOR) return renderRecommendation();
    screen({ title: 'Preporuke', back: '#/lokacija', html:
      '<p class="center muted">Računam tri scenarija…</p>' + '<div class="skeleton" aria-hidden="true"></div>'.repeat(3) });
    // Lanci bez adresa su najveći deo računanja, a nisu u planu: plan se
    // prikaže bez njih, a oni stignu odmah zatim.
    recommendation = await api('GET', `shopping-lists/${listId}/recommendations?latitude=${origin.lat}&longitude=${origin.lng}${travel}&unlocated=false`);
    recommendationFor = { key, at: Date.now() };
  } catch (error) {
    return failed('Preporuke', error, '#/preporuke');
  }
  renderRecommendation();
  loadUnlocated(listId, recommendation);
}

async function loadUnlocated(listId, result) {
  try {
    result.unlocatedPriceOptions = await api('GET',
      `shopping-lists/${listId}/recommendations/unlocated-price-options?date=${result.requestedDate}`);
  } catch (error) {
    return; // Samo poređenje: plan je već tu.
  }
  const section = document.getElementById('unlocated');
  if (recommendation === result && section) section.innerHTML = unlocatedSummary(result.unlocatedPriceOptions);
}

function renderRecommendation() {
  const result = recommendation;
  const origin = saved.get('origin');
  const scenarios = distinctScenarios(result);
  const s = scenarios.find(x => x.type === selectedType) || result.recommendedBalance;
  const stores = [...s.stores].sort((a, b) => a.stopOrder - b.stopOrder);
  const unresolved = s.items.filter(i => i.resultStatus !== 'AVAILABLE' && !onTheWay(s, i));
  const a = result.assumptions;
  screen({
    title: 'Preporuke',
    back: '#/lokacija',
    html: `
      <div class="card row">
        <span class="letter" style="border-radius:50%;background:var(--mint);color:var(--on-mint)">${icon.pin}</span>
        <div class="grow"><div class="label accent">Polazna tačka</div><div>${esc(originLabel(origin))}</div></div>
        <a class="btn" href="#/lokacija">Promeni</a>
      </div>
      <div class="scenarios">${scenarios.map(x => `
        <button class="scenario" aria-pressed="${x.type === s.type}" data-act="scenario" data-type="${x.type}" ${x.available ? '' : 'disabled'}>
          <span class="small muted">${scenarioShort[x.type]}</span>
          <span class="price">${x.available && x.totalCost != null ? whole(x.totalCost) : 'nema'}</span>
          <span class="small muted">ukupno</span>
          ${x.available ? `<span class="small muted">korpa ${whole(x.basketCost)}</span>
            <span class="small"><b>${x.coveredItems} od ${x.items.length}</b> ${plural(x.items.length, 'stavke', 'stavke', 'stavki')}</span>` : ''}
        </button>`).join('')}
      </div>
      <div class="card stripe">
        <div class="row" style="flex-wrap:wrap;gap:8px">${pill(scenarioBadge[s.type], 'pos')}
          ${s.available && s.savingsComparedWithSingleStore > 0 ? pill(`Ušteda ${whole(s.savingsComparedWithSingleStore)} ${market.currency}`, 'pos') : ''}</div>
        <div class="title">${scenarioTitle[s.type]}</div>
        ${s.available ? `
          <div class="big">${s.totalCost != null ? money(s.totalCost) : 'nema cene'}</div>
          ${s.totalCost != null ? `<p class="muted small">korpa ${money(s.basketCost)} + put i vreme ${money(s.totalCost - s.basketCost)}</p>` : ''}
          <div class="stats">
            <div><span class="small muted">Prodavnice</span><b>${s.stopCount}</b></div>
            <div><span class="small muted">Razdaljina</span><b>${distance(s.routeDistanceKm)}</b></div>
            <div><span class="small muted">Vreme</span><b>~${duration(s.routeDurationSeconds)}</b></div>
          </div>
          <div class="row"><span class="grow">Pokrivenost korpe</span><b style="color:var(--primary)">${s.coveredItems} od ${s.items.length} ${plural(s.items.length, 'stavke', 'stavke', 'stavki')}</b></div>`
          : `<p>${esc(s.explanation)}</p>`}
      </div>` + onTheWayNotice(s) + nearOnlyOffer(s, result) + balanceExplanation(s, result) +
      scenarioWarnings(s, result).map(w => notice(w, 'warn')).join('') +
      (s.available && stores.length ? `
        <a class="btn" href="${esc(mapsUrl(origin, stores, result.assumptions?.travelMode === 'WALKING'))}" target="_blank" rel="noopener">${icon.route}${stores.length === 1 ? 'Pregled puta do prodavnice' : `Pregled rute kroz ${stores.length} ${plural(stores.length, 'prodavnicu', 'prodavnice', 'prodavnica')}`}</a>
        <p class="muted small">Google Maps prvo prikaže rutu, a navigaciju pokrećeš ti.</p>` : '') +
      stores.map(store => {
        const items = s.items.filter(i => i.storeId === store.storeId);
        return `<div class="card flush">
          <div class="row top-align" style="padding:16px">
            <span class="badge">${store.stopOrder}</span>
            <div class="grow"><div class="name">${esc(store.retailerName)}</div>
              <div class="muted small">${esc(storeAddress(store))}</div>
              <div class="muted small">${distance(store.distanceFromPreviousKm)} · oko ${duration(store.durationFromPreviousSeconds)}</div></div>
            <span class="price accent">${money(items.reduce((sum, i) => sum + (i.lineTotal || 0), 0))}</span>
          </div>
          ${items.map(item => `<div class="list-row row top-align">
            <div class="grow"><div class="name">${esc(item.requestedName)}</div>
              ${item.productName && item.productName.toLowerCase() !== item.requestedName.toLowerCase() ? `<div class="muted small">${esc(item.productName)}</div>` : ''}
              ${quantityLine(item) ? `<div class="muted small">${esc(quantityLine(item))}</div>` : ''}
              ${stalePrice(item.priceDate, result.requestedDate) ? `<div class="muted small">Cena od ${date(item.priceDate)}</div>` : ''}
              ${manySmallPacks(item) ? pill('Mnogo malih pakovanja', 'warn') : ''}
              ${item.substituteNote ? `<div class="small" style="color:var(--on-amber)">${esc(item.substituteNote)}</div>` : ''}
              <div class="item-menu" id="item-menu-${item.itemId}" hidden>
                <button class="btn text small" style="padding:8px 0" data-act="wrong-product" data-item="${item.itemId}"
                  aria-label="Pogrešan proizvod za „${esc(item.requestedName)}“">Pogrešan proizvod</button></div></div>
            <span class="price">${item.lineTotal != null ? money(item.lineTotal) : 'nema'}</span>
            <button class="icon-action" data-act="item-menu" aria-expanded="false" aria-controls="item-menu-${item.itemId}"
              aria-label="Još za „${esc(item.requestedName)}“">${icon.more}</button></div>`).join('')}
        </div>`;
      }).join('') +
      (unresolved.length ? `<div class="section">Bez ponude ${pill(unresolved.length)}</div>` + unresolved.map(item => `
        <div class="card"><div class="row top-align"><div class="grow name">${esc(item.requestedName)}</div>${pill(unresolvedStatus[item.resultStatus], 'warn')}</div>
        <p class="muted small">${esc(item.explanation)}</p></div>`).join('') : '') +
      `<div id="unlocated">${unlocatedSummary(result.unlocatedPriceOptions)}</div>` +
      `<details class="card"><summary>Kako je računato</summary>
        ${[
          s.dataAsOf ? `Cene su iz cenovnika od ${date(s.dataAsOf)}; cenovnik ne govori da li je artikal na polici.` : 'Za ovaj scenario nema važećih cena.',
          s.disclaimer || result.disclaimer,
          s.available ? `Korpa ${money(s.basketCost)}, put ${money(s.travelCost)}, vreme ${money(s.timeCost)}, stajanja ${money(s.stopCost)}.` : null,
          `Put računamo ${money(a.costPerKm)} po kilometru, vreme ${money(a.valuePerHour)} po satu i ${money(a.costPerStop)} po stajanju.`,
          s.approximateRoute ? 'Udaljenosti su procena: vazdušna linija uvećana za ulice, bez saobraćaja.' : null,
          `Razmotreno ${counted(result.candidateStoreCount, 'prodavnica', 'prodavnice', 'prodavnica')} u krugu od ${decimal(a.candidateRadiusMeters / 1000, 1)} km.`
        ].filter(Boolean).map(line => `<p class="small">${esc(line)}</p>`).join('')}
        <a class="btn text small" href="#/vreme">Promeni cenu vremena i prevoza</a>
      </details>`,
    action: s.available ? `<button class="btn primary main" data-act="start-purchase">Započni kupovinu po ovom planu</button>` : ''
  });
}

// Lanci bez adresa: jedna rečenica za sve, prva tri (najveća pokrivenost) i ostalo na zahtev.
let unlocatedOpen = false;
function unlocatedSummary(options) {
  if (!options.length) return '';
  const shown = unlocatedOpen ? options : options.slice(0, 3);
  return `<div class="section">Lanci bez poznate adrese ${pill(options.length)}</div>` +
    notice('Ovi lanci ne objavljuju adrese prodavnica, pa nisu u planu. Cene su samo za poređenje.', 'warn') +
    `<div class="card flush">${shown.map(o => `<div class="list-row"><div class="row"><div class="grow name">${esc(o.retailerName)}</div>
      <span class="price">${Math.abs(o.highestBasketCost - o.lowestBasketCost) < 0.005 ? money(o.lowestBasketCost) : `${number(o.lowestBasketCost, 2)} – ${money(o.highestBasketCost)}`}</span></div>
      <div class="muted small">${o.coveredItems} od ${o.totalItems} ${plural(o.totalItems, 'stavke', 'stavke', 'stavki')}${o.priceListCount > 1
        ? ` · ${o.priceListCount} cenovnika, ne znamo koji važi kod tebe` : ''}</div></div>`).join('')}</div>` +
    (options.length > 3 ? `<button class="btn" data-act="unlocated-more" aria-expanded="${unlocatedOpen}">${unlocatedOpen
      ? 'Prikaži manje' : `Prikaži još ${options.length - 3}`}</button>` : '');
}

/** „Pogrešan proizvod" u planu: odluka se pamti kao odbijena, a zamena se bira u koraku 1. */
let pendingPick = null;
async function wrongProduct(itemId) {
  const planItem = recommendation && recommendation.recommendedBalance.items.find(i => i.itemId === itemId);
  const name = planItem ? planItem.requestedName : '';
  if (!confirm(`Označi proizvod za „${name}“ kao pogrešan i izaberi drugi?`)) return;
  const listId = saved.get('listId');
  const list = await api('GET', `shopping-lists/${listId}`);
  const item = list.items.find(i => i.id === itemId);
  if (!item) throw new Error('Stavke više nema na spisku.');
  // Vrsta proizvoda nema odluku o jednom proizvodu; njoj se samo bira drugi.
  if (item.matchingDecisionId && item.matchingRule !== 'FLEXIBLE_CATEGORY') {
    const shown = (distinctScenarios(recommendation).find(x => x.type === selectedType) || recommendation.recommendedBalance)
      .items.find(i => i.itemId === itemId);
    await api('PUT', `shopping-lists/${listId}/items/${itemId}/match`, {
      action: 'REJECT',
      note: `Pogrešan proizvod u planu (web)${shown?.productName ? ': ' + shown.productName : ''}.`.slice(0, 500)
    });
  }
  pendingPick = itemId;
  location.hash = '#/provera';
}

// ---------- Kupovina ----------

/** Kupuje se samo ono što ima prodavnicu u planu; stavke bez nje se ne broje. */
const purchaseItems = purchase => purchase.scenario.items.filter(i => i.storeId != null);
const purchaseBought = purchase => purchaseItems(purchase).filter(i => purchase.checked[i.itemId]).length;

function startPurchase() {
  const s = distinctScenarios(recommendation).find(x => x.type === selectedType) || recommendation.recommendedBalance;
  if (saved.get('purchase') && !confirm('Započni novu kupovinu? Napredak prethodne kupovine se briše.')) return;
  saved.set('purchase', { startedAt: new Date().toISOString(), listId: saved.get('listId'), scenario: s, checked: {},
    walking: recommendation.assumptions?.travelMode === 'WALKING' });
  location.hash = '#/kupovina';
}

function showPurchase() {
  const purchase = saved.get('purchase');
  if (!purchase) { location.hash = '#/spisak'; return; }
  const s = purchase.scenario;
  const total = purchaseItems(purchase).length;
  const bought = purchaseBought(purchase);
  const stores = [...s.stores].sort((a, b) => a.stopOrder - b.stopOrder);
  const row = item => {
    const done = Boolean(purchase.checked[item.itemId]);
    // Bez prodavnice nema šta da se štiklira: stavka samo kaže zašto je nema.
    if (item.storeId == null) return `<div class="list-row"><span class="name" style="display:block">${esc(item.requestedName)}</span>
      <span class="small ${onTheWay(s, item) ? 'muted' : ''}" style="display:block;${onTheWay(s, item) ? '' : 'color:var(--error)'}">${esc(item.explanation)}</span></div>`;
    return `<label class="check">
      <input type="checkbox" data-check="${item.itemId}" ${done ? 'checked' : ''} aria-label="Kupljeno: ${esc(item.requestedName)}">
      <span class="grow"><span class="name ${done ? 'strike' : ''}" style="display:block">${esc(item.requestedName)}</span>
        ${item.productName && item.productName.toLowerCase() !== item.requestedName.toLowerCase() ? `<span class="muted small" style="display:block">${esc(item.productName)}</span>` : ''}
        ${quantityLine(item) ? `<span class="muted small" style="display:block">${esc(quantityLine(item))}</span>` : ''}
        ${item.substituteNote ? `<span class="small" style="display:block;color:var(--on-amber)">${esc(item.substituteNote)}</span>` : ''}
        ${item.storeId == null ? `<span class="small ${onTheWay(s, item) ? 'muted' : ''}" style="display:block;${onTheWay(s, item) ? '' : 'color:var(--error)'}">${esc(item.explanation)}</span>` : ''}</span>
      ${item.lineTotal != null ? `<span class="price ${done ? 'strike' : ''}">${money(item.lineTotal)}</span>` : ''}
    </label>`;
  };
  const unresolved = s.items.filter(i => i.storeId == null);
  screen({
    title: 'U kupovini',
    subtitle: `Plan od ${date(purchase.startedAt)} · ${scenarioTitle[s.type]}`,
    back: '#/spisak',
    html: `
      <div class="card">
        <div class="row top-align">
          <div class="grow"><div class="label">Napredak kupovine</div><div class="title" data-bought>Kupljeno ${bought} od ${total}</div></div>
          <div style="text-align:right"><div class="small muted">planirano</div><div class="price accent">${whole(s.basketCost)} ${market.currency}</div></div>
        </div>
        <div class="progress"><span data-progress style="width:${total ? bought / total * 100 : 0}%"></span></div>
        <p class="muted small">Cene u sačuvanom planu se ne osvežavaju.</p>
        <a class="card soft row" href="#/kartice" style="text-decoration:none;color:inherit;padding:10px 12px">
          <span style="color:var(--primary)">${icon.card}</span><span class="grow">Lojalti kartice za kasu — prikaži barkod</span>${icon.chevron}</a>
      </div>` +
      (!s.complete && s.items.some(i => i.storeId == null && !onTheWay(s, i))
        ? notice('Plan nije potpun. Stavke bez ponude su na dnu.', 'warn') : '') +
      stores.map(store => {
        const items = s.items.filter(i => i.storeId === store.storeId);
        const done = items.filter(i => purchase.checked[i.itemId]).length;
        return `<div class="card flush" data-store>
          <div class="row" style="padding:12px 8px 12px 16px">
            <span class="badge">${store.stopOrder}</span>
            <div class="grow"><div class="name">${esc(store.retailerName)}</div><div class="muted small">${esc(storeAddress(store))}</div></div>
            <b data-store-count>${done}/${items.length}</b>
            <a class="icon-action" style="color:var(--primary)" href="${esc(mapsUrl(null, [store], purchase.walking))}" target="_blank" rel="noopener" aria-label="Put do prodavnice ${esc(store.retailerName)}">${icon.route}</a>
          </div>
          ${items.map(row).join('')}
        </div>`;
      }).join('') +
      (unresolved.length ? `<div class="section">${unresolved.every(i => onTheWay(s, i)) ? 'Kupi usput' : 'Bez prodavnice'} ${pill(unresolved.length)}</div><div class="card flush">${unresolved.map(row).join('')}</div>` : '') +
      `<button class="btn" data-act="finish-purchase">Završi kupovinu</button>
       <p class="muted small">Napredak kupovine čuva ovaj pregledač; originalni spisak ostaje nepromenjen.</p>`
  });
}

// ---------- Cene proizvoda ----------

let search = { query: '', onSale: false, page: null, items: [], error: null };

// Jedno slovo nađe skoro ceo katalog i traje sekundama; server ga isto odbija.
const searchable = query => (String(query).match(/[\p{L}\p{N}]/gu) || []).length >= 2;

/**
 * Pretraga dok se kuca: kreće 300 ms posle poslednjeg slova, kao u aplikaciji,
 * a zahtev za stariji upit se otkazuje, pa server radi samo za ono što se čita.
 */
function liveQuery() {
  let timer = null;
  let controller = null;
  return {
    later(work) { clearTimeout(timer); timer = setTimeout(work, 300); },
    cancel() { clearTimeout(timer); controller?.abort(); controller = null; },
    signal() { this.cancel(); controller = new AbortController(); return controller.signal; }
  };
}
const searchTyping = liveQuery();
const pickTyping = liveQuery();

function searchLocation() {
  const origin = saved.get('origin');
  // Zaokruženo na ~1 km, kao u aplikaciji: dovoljno za redosled lanaca.
  return origin ? `&latitude=${origin.lat.toFixed(2)}&longitude=${origin.lng.toFixed(2)}` : '';
}

/** Tri najjeftinija lanca, a lanac na akciji uvek među njima, kao u aplikaciji. */
function shortList(offers) {
  // Lanac čiju cenu treba proveriti ide na kraj, ma koliko izgledala niska.
  const rank = a => [a.priceNeedsCheck ? 1 : 0, a.minimumEffectivePrice ?? Number.MAX_VALUE];
  const sorted = [...offers].sort((a, b) => {
    const [x, y] = [rank(a), rank(b)];
    return x[0] - y[0] || x[1] - y[1];
  });
  const first = sorted.slice(0, 3);
  const onSale = a => a.discountPercent && !a.priceNeedsCheck;
  const sale = sorted.find(onSale);
  return first.some(onSale) || !sale ? first : [...first.slice(0, 2), sale];
}

function renderSearch(loading = false) {
  // Dok se kuca menjaju se samo rezultati, da polje zadrži slovo i kursor.
  const box = view.querySelector('#search-results');
  if (box) {
    box.innerHTML = searchResults(loading);
    box.setAttribute('aria-busy', String(loading));
    return;
  }
  screen({
    title: 'Cene',
    html: `
      <form data-form="search" role="search">
        <input name="q" type="search" enterkeyhint="search" placeholder="Naziv ili barkod, npr. kravica mleko" value="${esc(search.query)}" autocomplete="off"
          aria-label="Pretraga proizvoda" aria-describedby="search-hint" aria-controls="search-results">
        <p class="muted small" id="search-hint">Rezultati stižu dok kucaš, od 2 slova.</p>
      </form>
      <label class="toggle"><input type="checkbox" data-sale-filter ${search.onSale ? 'checked' : ''}>Samo proizvodi na akciji</label>
      <p class="muted small">Cena u cenovniku nije potvrda da proizvoda ima na stanju.</p>
      <div id="search-results" aria-busy="${loading}">${searchResults(loading)}</div>`
  });
}

function searchResults(loading) {
  const page = search.page;
  return (!page && !loading ? salesShortcut() : '') +
      (search.error ? notice(search.error, 'err') : '') +
      (page && page.correctedQuery ? `<p class="small muted">Prikazujem rezultate za „${esc(page.correctedQuery)}“.</p>` : '') +
      (page && !search.items.length ? `<div class="card soft center">${search.onSale
        ? 'Nema proizvoda na akciji za ovaj upit.' : `Nema proizvoda za „${esc(search.query)}“.`}</div>` : '') +
      search.items.map((p, index) => `
        <div class="card">
          <div class="name">${esc(p.name)}</div>
          <div class="muted small">${esc([p.brand, p.quantityValue && p.baseUnit ? amountLabel(p.quantityValue, p.baseUnit, p.packageCount) : null].filter(Boolean).join(' · '))}</div>
          ${page.nearbyChecked && p.hasUsablePrice ? (p.nearestStoreMeters == null
            ? pill('Nema ga u radnjama blizu tebe', 'warn')
            : `<div class="muted small">Najbliža radnja koja ga ima: ${distance(p.nearestStoreMeters / 1000)}</div>`) : ''}
          ${shortList(p.availability || []).map(a => `<div class="${a.discountPercent ? tier(a.discountPercent) : ''}">
            <div class="row small"><span class="grow">${esc(a.retailerName)}</span>
            <span class="muted">${shortDate(a.latestPriceDate)}</span>
            <b class="${a.discountPercent ? 'sale-price' : ''}">${a.minimumEffectivePrice != null ? 'od ' + money(a.minimumEffectivePrice) : 'bez cene'}</b></div>
            ${a.discountPercent ? saleLine(a.discountPercent, a.saleRegularPrice, a.saleEndDate) : ''}</div>`).join('')}
          <div class="btn-row">
            ${p.canonicalProductId ? `<a class="btn text" href="#/proizvod/${p.canonicalProductId}">Sve cene</a>` : ''}
            <button class="btn primary" data-act="add-product" data-index="${index}">${icon.add}Na spisak</button>
          </div>
        </div>`).join('') +
      (loading ? spinner('Tražim…') : '') +
      (page && page.hasNext && !loading ? `<button class="btn" data-act="more">Prikaži još</button>` : '');
}

async function runSearch(more = false) {
  const signal = searchTyping.signal();
  // Kucanje koje je stiglo posle odlaska sa Cena ne crta ništa preko drugog ekrana.
  if (!view.querySelector('#search-results')) return;
  if (!search.query.trim()) {
    search.page = null;
    search.items = [];
    search.error = null;
    return renderSearch();
  }
  if (!searchable(search.query)) {
    search.page = null;
    search.items = [];
    search.error = 'Upiši bar 2 slova ili broja.';
    return renderSearch();
  }
  const next = more ? search.page.page + 1 : 0;
  if (!more) search.items = [];
  search.error = null;
  renderSearch(true);
  try {
    const page = await api('GET', `products/search?query=${encodeURIComponent(search.query.trim())}&page=${next}&limit=10${
      search.onSale ? '&onSale=true' : ''}${searchLocation()}`, undefined, false, signal);
    search.page = page;
    search.items = more ? search.items.concat(page.items) : page.items;
  } catch (error) {
    if (error.name === 'AbortError') return;
    search.error = error.message;
  }
  if (view.querySelector('#search-results')) renderSearch();
}

document.addEventListener('input', event => {
  const field = event.target;
  if (field.matches('[data-form=search] input[name=q]')) {
    search.query = field.value;
    // Ispod 2 slova nema pretrage ni opomene dok se još kuca; Enter je ipak objasni.
    if (searchable(search.query)) return searchTyping.later(() => runSearch());
    searchTyping.cancel();
    search.page = null;
    search.items = [];
    search.error = null;
    renderSearch();
  } else if (field.id === 'pick-q' && picking) {
    picking.query = field.value;
    if (searchable(field.value)) return pickTyping.later(() => runPick(field.value));
    pickTyping.cancel();
    Object.assign(picking, { results: null, loading: false, error: null });
    renderPick();
  }
});

async function addProduct(product) {
  await loadList().then(list => { listState.list = list; });
  const added = await api('POST', `shopping-lists/${listState.list.id}/items`, {
    name: product.name,
    canonicalProductId: product.productFamilyId ? null : product.canonicalProductId,
    productFamilyId: product.productFamilyId || null,
    quantity: 1,
    matchingRule: product.productFamilyId ? 'PRODUCT_FAMILY' : 'EXACT_PRODUCT'
  });
  // Spisak u memoriji zna za novu stavku, pa Akcije kažu „Na spisku".
  if (added?.id) listState.list.items.push(added);
  toast(`Na spisku: ${product.name}`);
}

const offerScope = o => o.priceScope === 'STORE' ? (o.storeName || 'Jedan objekat')
  : o.priceScope === 'STORE_FORMAT' ? `Format ${o.storeFormatName || 'nepoznat'}` : 'Svi objekti lanca';
const isCaseOf = (offer, product) => offer.packageCount > product.packageCount;

function unitPriceLabel(offer, product) {
  const quantity = product.quantityValue > 0 ? product.quantityValue : null;
  const unit = product.baseUnit;
  if (!quantity || !unit) return offer.unitPrice != null ? `jed. ${money(offer.unitPrice)}` : null;
  const amount = quantity / Math.max(product.packageCount, 1) * Math.max(offer.packageCount, 1);
  if (unit === 'g') return `${money(offer.effectivePrice * 1000 / amount)}/kg`;
  if (unit === 'ml') return `${money(offer.effectivePrice * 1000 / amount)}/l`;
  if (unit === 'piece') return amount > 1 ? `${money(offer.effectivePrice / amount)}/kom` : null;
  return offer.unitPrice != null ? `jed. ${money(offer.unitPrice)}` : null;
}

// Detalji se otvaraju iz pretrage i iz akcija; Nazad vodi tamo odakle se došlo.
let productFrom = '#/cene';
// Akcije se otvaraju sa spiska i iz cena; Nazad vodi tamo odakle se došlo.
let salesFrom = '#/cene';

async function showProduct(id) {
  screen({ title: 'Cene proizvoda', back: productFrom, html: spinner('Učitavam ponude…') });
  let product;
  try {
    product = await api('GET', `products/${id}?historyLimit=30`);
  } catch (error) {
    return failed('Cene proizvoda', error, `#/proizvod/${id}`);
  }
  const offers = [...product.offers].sort((a, b) =>
    (a.priceNeedsCheck - b.priceNeedsCheck) || (!!a.priceStale - !!b.priceStale) || (isCaseOf(a, product) - isCaseOf(b, product)) || (a.effectivePrice - b.effectivePrice));
  // Stara cena (cenovnik stariji nego što lanac inače kasni) ne ulazi u poređenje.
  const comparable = offers.filter(o => !o.priceNeedsCheck && !o.priceStale && !isCaseOf(o, product));
  // Lanci, ne prodavnice: devet METRO objekata je jedan lanac.
  const chains = new Set(comparable.map(o => o.retailerName)).size;
  const low = comparable[0];
  const high = comparable[comparable.length - 1];
  const stat = (label, price, note, strong) => `<div style="flex:1;min-width:0;padding:8px;border-radius:10px;${strong ? 'background:var(--card)' : ''}">
    <div class="label">${label}</div><div class="price ${strong ? 'accent' : ''}">${money(price)}</div><div class="small muted">${esc(note)}</div></div>`;
  window.currentProduct = product;
  screen({
    title: 'Cene proizvoda',
    back: productFrom,
    html: `
      <div class="card">
        ${product.brand ? `<div class="label accent">${esc(product.brand)}</div>` : ''}
        <div class="title">${esc(product.name)}</div>
        <p class="muted small">${esc([product.brand, product.quantityValue && product.baseUnit ? amountLabel(product.quantityValue, product.baseUnit, product.packageCount) : null, product.barcode ? 'barkod ' + product.barcode : null].filter(Boolean).join(' · '))}</p>
        <p class="muted small">Cene za ${date(product.requestedDate)}</p>
        ${chains > 1 ? `<div class="row" style="background:var(--low);border-radius:12px;padding:4px;gap:4px;align-items:stretch">
          ${stat('Najniža', low.effectivePrice, low.retailerName, true)}
          ${stat('Prosečna', comparable.reduce((s, o) => s + o.effectivePrice, 0) / comparable.length, counted(chains, 'lanac', 'lanca', 'lanaca'), false)}
          ${stat('Najviša', high.effectivePrice, high.retailerName, false)}</div>` : ''}
        <button class="btn primary" data-act="add-current">${icon.add}Dodaj na spisak</button>
      </div>
      <div class="section">Aktuelne ponude ${pill(offers.length)}</div>` +
      (offers.length ? offers.map((o, index) => {
        const cheapest = index === 0 && offers.length > 1 && !o.priceNeedsCheck && !o.priceStale && !isCaseOf(o, product);
        const single = product.packageCount;
        const off = o.priceNeedsCheck ? null : discountPercent(o.regularPrice, o.effectivePrice);
        return `<div class="card ${cheapest ? 'selected' : ''} ${off ? tier(off) : ''}"><div class="row top-align">
          <span class="letter">${esc(o.retailerName.trim().charAt(0).toUpperCase())}</span>
          <div class="grow"><div class="name">${esc(o.retailerName)}</div>
            <div class="muted small">${esc(offerScope(o))} · cene od ${shortDate(o.priceDate)}</div>
            ${off ? saleLine(off, o.regularPrice, o.saleEndDate) : ''}
            ${isCaseOf(o, product) ? `<div class="small" style="color:var(--on-amber)">Pakovanje od ${o.packageCount / single} kom · ${money(o.effectivePrice * single / o.packageCount)} po komadu</div>` : ''}
            ${o.priceNeedsCheck ? `<div class="muted small">Manje od pola uobičajene cene u drugim lancima</div>${pill('Proveri cenu', 'warn')}` : ''}
            ${o.priceStale ? `<div class="muted small">Lanac nije objavio noviji cenovnik</div>${pill('Stara cena', 'warn')}` : ''}
            ${cheapest ? pill('Najbolja cena', 'pos') : ''}</div>
          <div style="text-align:right"><div class="price ${off ? 'sale-price' : cheapest ? 'accent' : ''}" style="font-size:18px">${money(o.effectivePrice)}</div>
            ${unitPriceLabel(o, product) ? `<div class="muted small">${unitPriceLabel(o, product)}</div>` : ''}</div>
        </div></div>`;
      }).join('') : notice('Za ovaj proizvod još nema važećih cena.')) +
      (product.priceHistory.length ? `<div class="section">Poslednje promene cena</div><div class="card flush">${product.priceHistory.map(p => `
        <div class="list-row row"><div class="grow"><div>${esc(p.retailerName)}</div>
        <div class="muted small">${esc([date(p.priceDate), p.storeName, p.storeFormatName].filter(Boolean).join(' · '))}</div></div>
        ${p.previousEffectivePrice != null ? `<span class="strike muted small" aria-label="Pre: ${money(p.previousEffectivePrice)}">${money(p.previousEffectivePrice)}</span><span class="muted" aria-hidden="true">→</span>` : ''}
        <span class="price">${money(p.effectivePrice)}</span></div>`).join('')}</div>` : '')
  });
}

// ---------- Akcije ----------

let sales = { sort: 'DISCOUNT', category: null, allChains: false, place: null, page: null, items: [], categories: [], error: null };
const saleSorts = { DISCOUNT: 'Najveći popust', SAVING: 'Najveća ušteda', PRICE: 'Najniža cena' };

/** Sa sačuvanom polaznom tačkom samo radnje u blizini, osim kad kupac izabere „Svi lanci". */
const salesPlace = () => sales.allChains ? '' : searchLocation();

/** Lista se pamti dok se ide na detalje i nazad; nova kad se promeni mesto. */
function showSales() {
  if (sales.page && sales.place === salesPlace()) return renderSales();
  loadList().then(list => { listState.list = list; if (location.hash === '#/akcije' && sales.page) renderSales(); }).catch(() => {});
  return loadSales();
}

/** Proizvodi koji su već na spisku, da njihova kartica kaže „Na spisku". */
function onListKeys() {
  const keys = new Set();
  for (const item of listState.list?.items || []) {
    if (item.matchingRule === 'PRODUCT_FAMILY' && item.matchedProductFamilyId) keys.add('f' + item.matchedProductFamilyId);
    if (item.matchingRule === 'EXACT_PRODUCT' && item.matchedCanonicalProductId) keys.add('c' + item.matchedCanonicalProductId);
  }
  return keys;
}
/** I kad je dodat iz detalja proizvoda (tačan proizvod), ne samo sa akcija. */
const isOnList = (p, keys) => keys.has('f' + p.productFamilyId) || keys.has('c' + p.canonicalProductId);

async function loadSales(more = false) {
  const next = more ? sales.page.page + 1 : 0;
  if (!more) {
    sales.items = []; sales.page = null;
  }
  sales.error = null;
  sales.place = salesPlace();
  renderSales(true);
  try {
    const page = await api('GET', `products/on-sale?sort=${sales.sort}&page=${next}&limit=20${
      sales.category ? '&category=' + encodeURIComponent(sales.category) : ''}${sales.place}`);
    sales.page = page;
    sales.items = more ? sales.items.concat(page.items) : page.items;
    if (!more) sales.categories = page.categories;
  } catch (error) {
    sales.error = error.message;
  }
  renderSales();
}

function saleCard(p, index, keys) {
  return `<div class="card sale ${tier(p.discountPercent)}">
    <div class="row top-align">
      <div class="grow"><div class="name">${esc(p.name)}</div>
        <div class="muted small">${esc([p.brand, p.quantityValue && p.baseUnit ? amountLabel(p.quantityValue, p.baseUnit, p.packageCount) : null].filter(Boolean).join(' · '))}</div></div>
      ${saleBadge(p.discountPercent, true)}
    </div>
    <div class="sale-line"><span class="price sale-price" style="font-size:22px">${money(p.salePrice)}</span>
      <span class="strike" aria-label="Redovna cena ${money(p.regularPrice)}">${money(p.regularPrice)}</span>
      ${p.saleEndDate ? `<span class="muted small">do ${shortDate(p.saleEndDate)}</span>` : ''}</div>
    <div class="small">u lancu ${esc(p.retailerName)}${p.nearestStoreMeters != null ? `<span class="muted"> · najbliža radnja ${distance(p.nearestStoreMeters / 1000)}</span>` : ''}</div>
    ${p.otherChainCount > 0 ? `<div class="muted small">na akciji i u još ${counted(p.otherChainCount, 'lancu', 'lanca', 'lanaca')}</div>` : ''}
    <div class="btn-row">
      ${p.canonicalProductId ? `<a class="btn text" href="#/proizvod/${p.canonicalProductId}">Cene u lancima</a>` : ''}
      ${isOnList(p, keys) ? `<button class="btn" disabled>Na spisku</button>`
        : `<button class="btn primary" data-act="add-sale" data-index="${index}">${icon.add}Na spisak</button>`}
    </div>
  </div>`;
}

function renderSales(loading = false) {
  const page = sales.page;
  const chip = (act, value, label, pressed) =>
    `<button class="chip" data-act="${act}" data-value="${esc(value)}" aria-pressed="${pressed}">${esc(label)}</button>`;
  screen({
    title: 'Akcije',
    subtitle: page ? (page.nearbyChecked ? 'Radnje do 10 km od tebe' : 'Svi lanci') : null,
    back: salesFrom,
    html:
      (!saved.get('origin') ? `<button class="card soft row" style="text-align:left;cursor:pointer" data-act="sales-locate">
        <span class="letter" style="border-radius:50%;background:var(--mint);color:var(--on-mint)">${icon.pin}</span>
        <span class="grow"><span class="name" style="display:block">Samo radnje blizu mene</span>
        <span class="muted small">${browserName} će pitati za dozvolu</span></span>${icon.chevron}</button>`
        : `<div class="chips" role="group" aria-label="Radnje">${chip('sale-scope', 'near', 'Blizu mene', !sales.allChains)}${
          chip('sale-scope', 'all', 'Svi lanci', sales.allChains)}</div>`) +
      `<div class="chips" role="group" aria-label="Redosled">${Object.entries(saleSorts).map(([key, label]) =>
        chip('sale-sort', key, label, key === sales.sort)).join('')}</div>` +
      (sales.categories.length ? `<div class="chips" role="group" aria-label="Kategorija">${chip('sale-category', '', 'Sve', !sales.category)}${
        sales.categories.map(c => chip('sale-category', c.code, `${c.name} (${c.productCount})`, c.code === sales.category)).join('')}</div>` : '') +
      (sales.error ? notice('Akcije trenutno nisu dostupne. ' + sales.error, 'err') : '') +
      (page ? `<div class="section">${counted(page.totalElements, 'proizvod', 'proizvoda', 'proizvoda')} na akciji</div>` : '') +
      (page && !sales.items.length ? `<div class="card soft center">${page.nearbyChecked
        ? 'U radnjama blizu tebe danas nema akcija za ovaj izbor.' : 'Danas nema proizvoda na akciji za ovaj izbor.'}</div>` : '') +
      (keys => sales.items.map((p, index) => saleCard(p, index, keys)).join(''))(onListKeys()) +
      (loading ? spinner('Učitavam akcije…') : '') +
      (page && page.hasNext && !loading ? `<button class="btn" data-act="sales-more">Prikaži još</button>` : '') +
      `<p class="muted small">Cene su iz zvaničnih cenovnika. Merodavna je cena u prodavnici.</p>`
  });
  // Izabrana kategorija ostaje u vidnom polju trake sa čipovima.
  view.querySelectorAll('.chips').forEach(row => {
    const active = row.querySelector('[aria-pressed="true"]');
    if (active) row.scrollLeft = active.offsetLeft - row.offsetLeft - (row.clientWidth - active.offsetWidth) / 2;
  });
}

function salesLocate() {
  if (!navigator.geolocation) return toast('Ovaj pregledač ne daje lokaciju.');
  navigator.geolocation.getCurrentPosition(
    position => { saved.set('origin', { lat: position.coords.latitude, lng: position.coords.longitude }); sales.allChains = false; loadSales(); },
    error => toast(error.code === 1 ? `${browserName} nema dozvolu za lokaciju.` : 'Lokacija trenutno nije dostupna.', null, null, true),
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 300000 }
  );
}

// ---------- Lojalti kartice ----------

let cards = { list: [], adding: false, shown: null, error: null };

async function showCards() {
  screen({ title: 'Lojalti kartice', html: spinner('Učitavam kartice…') });
  try {
    cards.list = await api('GET', 'loyalty-cards');
    cards.error = null;
  } catch (error) {
    cards.error = error.message;
  }
  renderCards();
}

function codeBox(card) {
  const svg = barcodeSvg(card.cardNumber, card.barcodeFormat);
  return `<div class="code-box">${svg || '<p class="center">Ovaj oblik koda web verzija ne crta. Kasirka može da ukuca broj.</p>'}
    <div class="number">${esc(card.cardNumber)}</div></div>`;
}

function renderCards() {
  const [first, ...rest] = cards.list;
  const shown = cards.list.find(c => c.id === cards.shown);
  screen({
    title: 'Lojalti kartice',
    html:
      (cards.error ? notice(cards.error, 'err') : '') +
      `<div class="row"><span class="grow muted">Barkodovi za kasu</span>
        <button class="btn primary" data-act="add-card">${icon.add}Dodaj karticu</button></div>` +
      (cards.adding ? `<form class="card" data-form="card">
          <label class="label" for="card-name">Naziv</label>
          <input id="card-name" name="name" type="text" placeholder="npr. Moj Maxi" required>
          <label class="label" for="card-number">Broj sa kartice</label>
          <input id="card-number" name="number" inputmode="text" required>
          <select name="format" aria-label="Oblik koda">
            <option value="CODE_128">Crtični kod (najčešće)</option>
            <option value="EAN_13">EAN-13 (13 cifara)</option>
            <option value="QR_CODE">QR kod</option>
          </select>
          <div class="btn-row"><button type="button" class="btn" data-act="cancel-card">Odustani</button><button class="btn primary">Sačuvaj</button></div>
        </form>` : '') +
      (first ? `
        <button class="card hero" style="text-align:left;cursor:pointer;border-radius:24px" data-act="show-card" data-id="${first.id}">
          <span class="title">${esc(first.name)}</span>${codeBox(first)}<span class="small">Dodirni za veći kod</span>
        </button>` +
        (rest.length ? `<div class="section">Tvoj novčanik</div>` : '') +
        rest.map(c => `<button class="card row" style="text-align:left;cursor:pointer" data-act="show-card" data-id="${c.id}">
          <span class="letter">${esc(c.name.trim().charAt(0).toUpperCase())}</span>
          <span class="grow"><span class="name" style="display:block">${esc(c.name)}</span><span class="muted small">${esc(c.cardNumber)}</span></span>
          ${pill('Barkod', 'pos')}</button>`).join('')
        : `<p class="muted">Dodaj kartice koje nosiš u novčaniku pa ih na kasi otvori odavde. Kartice stoje uz nalog, kao i u Android aplikaciji.</p>`) +
      (shown ? `<div class="overlay" data-act="close-card"><div class="card" data-stop>
          <div class="title">${esc(shown.name)}</div>${codeBox(shown)}
          <p class="muted small center">Ako kod ne prolazi, kasirka može da ukuca broj.</p>
          <div class="btn-row"><button class="btn danger" data-act="delete-card" data-id="${shown.id}">Obriši karticu</button>
          <button class="btn primary" data-act="close-card">Zatvori</button></div></div></div>` : '')
  });
  const field = view.querySelector('[data-form=card] input');
  if (field) field.focus();
}

// ---------- Više ----------

function showMore(_, __, loaded = false) {
  const standalone = navigator.standalone || matchMedia('(display-mode: standalone)').matches;
  // Prvo ono što se zna, pa sveže stanje naloga čim stigne.
  if (!loaded) loadAccount().then(() => { if (location.hash === '#/vise') showMore(null, null, true); });
  const link = (href, title, text) => `<a class="card row" href="${href}" style="text-decoration:none;color:inherit">
    <span class="grow"><span class="name" style="display:block">${title}</span><span class="muted small">${text}</span></span>${icon.chevron}</a>`;
  screen({
    title: 'Više',
    html:
      (standalone || !appleMobile ? '' : `<div class="card hero">
        <div class="title">Dodaj na početni ekran</div>
        <p>U Safari-ju dodirni <b>Podeli</b> pa <b>Dodaj na početni ekran</b>. Otvara se kao aplikacija, a spisak i kartice ostaju i kad Safari čisti stare podatke sajtova.</p>
        ${account.state?.signedIn ? '' : '<p class="small">Aplikacija na početnom ekranu ima svoje podatke, odvojene od Safari-ja. Da spisak pređe u nju, prvo se prijavi preko Google-a ovde, pa se prijavi i u njoj.</p>'}
      </div>`) +
      accountCard() +
      `<a class="card row" href="#/racun" style="text-decoration:none;color:inherit">
        <span class="grow"><span class="name" style="display:block">Dodaj račun</span>
        <span class="muted small">Screenshot ili PDF digitalnog računa</span></span>${icon.chevron}</a>` +
      link('#/potrosnja', 'Potrošnja', 'Koliko trošiš mesečno, po prodavnicama i kategorijama') +
      link('#/domacinstvo', 'Domaćinstvo', account.state?.household ? 'Delite spisak, račune i kartice' : 'Pozovi ukućanina da delite spisak') +
      link('#/vreme', 'Vreme i prevoz', 'Koliko plan računa sat u putu, kilometar kolima i stajanje') +
      themeCard() +
      `<div class="card">
        <div class="title">O aplikaciji</div>
        <p>Pametna kupovina ${VERSION}, web verzija</p>
        <p class="muted small">Cene su iz zvaničnih cenovnika trgovaca. Merodavna je cena u prodavnici.</p>
        <p class="muted small">Web verzija nema skeniranje kamerom (račun se dodaje sa screenshot-a ili PDF-a) ni obaveštenja o pojeftinjenju — to ima Android aplikacija.</p>
        <div class="btn-row"><a class="btn text" href="/privatnost">Politika privatnosti</a><a class="btn text" href="/uslovi">Uslovi korišćenja</a></div>
      </div>
      <div class="card">
        <div class="name">Broj uređaja (za pitanja o podacima)</div>
        <p class="muted small" style="overflow-wrap:anywhere">${esc(saved.get('session')?.deviceId ? 'PK-' + saved.get('session').deviceId : '…')}</p>
        <button class="btn danger" data-act="delete-account">${account.state?.household ? 'Izađi iz domaćinstva i obriši podatke' : 'Obriši moje podatke'}</button>
      </div>`
  });
}

const themes = [[null, 'Sistemska'], ['light', 'Svetla'], ['dark', 'Tamna'], ['black', 'Crna']];

function themeCard() {
  const current = saved.get('theme', null);
  return `<div class="card">
    <div class="title">Tema</div>
    <div class="theme-choice" role="group" aria-label="Tema">
      ${themes.map(([value, label]) => `<button class="chip" data-act="theme" data-value="${value || ''}" aria-pressed="${current === value}">${label}</button>`).join('')}
    </div>
    <p class="muted small">Sistemska prati telefon. Crna štedi bateriju na OLED ekranima.</p>
  </div>`;
}

// Cena vremena, kilometra i stajanja: plan njima meri da li se isplati vožnja do jeftinije radnje.
let tripCosts = null;
let tripFrom = '#/vise';
async function showTripCosts() {
  screen({ title: 'Vreme i prevoz', back: tripFrom, html: spinner('Učitavam…') });
  try {
    tripCosts = await api('GET', 'accounts/me/trip-costs');
  } catch (error) {
    if (currentRoute === 'vreme') failed('Vreme i prevoz', error, '#/vreme');
    return;
  }
  // Ko je u međuvremenu otišao na drugi ekran, ostaje tamo.
  if (currentRoute === 'vreme') renderTripCosts();
}

// Iznos u polju bez razdvajanja hiljada, da se pročita isto kao što je upisan.
const tripInput = value => number(value, market.currencyMinorUnits).split(separators.group).join('');

// „1.000“ je u Srbiji hiljada: tačka razdvaja hiljade samo kad grupe stvarno imaju po tri cifre.
function parseTripAmount(text) {
  const raw = String(text ?? '').trim().replace(/\s/g, '');
  const escape = c => c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const grouped = new RegExp(`^\\d{1,3}(${escape(separators.group)}\\d{3})+(${escape(separators.decimal)}\\d*)?$`).test(raw);
  return parseNumber(grouped ? raw.split(separators.group).join('') : raw);
}

function renderTripCosts(message = null) {
  const t = tripCosts;
  screen({
    title: 'Vreme i prevoz',
    back: tripFrom,
    html: `
      <div class="card soft">
        <div class="title">Šta ti vredi više: vreme ili ušteda</div>
        <p class="muted small">Plan sabira korpu, gorivo, vreme u putu i stajanja. Jeftinija korpa dalje od tebe pobeđuje samo ako ušteda pokrije put.</p>
      </div>
      <form class="card" data-form="trip-costs">
        <label class="label" for="trip-hour">Sat u putu (${esc(t.currency)})</label>
        <input id="trip-hour" name="hour" inputmode="decimal" value="${esc(tripInput(t.valuePerHour))}" required>
        <label class="label" for="trip-km">Kilometar kolima (${esc(t.currency)})</label>
        <input id="trip-km" name="km" inputmode="decimal" value="${esc(tripInput(t.costPerKm))}" required>
        <p class="muted small">Gorivo i trošak auta. Peške kilometar ne košta ništa.</p>
        <label class="label" for="trip-stop">Jedno stajanje (${esc(t.currency)})</label>
        <input id="trip-stop" name="stop" inputmode="decimal" value="${esc(tripInput(t.costPerStop))}" required>
        <p class="muted small">Podrazumevano je ${money(t.defaultValuePerHour)} po satu, ${money(t.defaultCostPerKm)} po kilometru i ${money(t.defaultCostPerStop)} po stajanju. Veći iznos znači manje vožnje i manje radnji, a 0 znači da plan to ne računa.</p>
        <div class="btn-row">${t.customized ? '<button type="button" class="btn" data-act="trip-reset">Vrati podrazumevano</button>' : ''}<button class="btn primary">Sačuvaj</button></div>
      </form>
      ${message ? `<div class="notice pos" role="status" data-trip-saved>${esc(message)}</div>` : ''}`
  });
}

async function saveTripCosts(valuePerHour, costPerStop, costPerKm) {
  tripCosts = await api('PUT', 'accounts/me/trip-costs', { valuePerHour, costPerStop, costPerKm });
  // Plan je računat po staroj ceni.
  recommendation = null;
  recommendationFor = null;
  if (currentRoute !== 'vreme') return;
  renderTripCosts(tripCosts.customized
    ? `Sačuvano. Plan računa ${money(tripCosts.valuePerHour)} po satu, ${money(tripCosts.costPerKm)} po kilometru i ${money(tripCosts.costPerStop)} po stajanju.`
    : 'Sačuvano. Plan opet računa podrazumevano vreme, kilometar i stajanja.');
}

function tripAmount(data, name, max) {
  const value = parseTripAmount(data.get(name));
  if (value == null || value < 0 || value > max) {
    throw fieldError(name, `Upiši iznos od 0 do ${tripInput(max)} ${market.currency}.`);
  }
  return value;
}

async function deleteAccount() {
  const shared = account.state?.household;
  if (!confirm(shared
    ? 'Ovaj pregledač izlazi iz domaćinstva i briše sve sa sebe. Zajednički spisak, računi i kartice ostaju ukućanima.'
    : 'Obrisati spisak, račune, kartice i sve podatke ovog uređaja sa servera? Ovo se ne može vratiti.')) return;
  await api('DELETE', 'accounts/me');
  clearBrowser();
  account = { state: null, devices: [], error: null };
  // Bez odlaska na spisak: on bi odmah napravio nov nalog.
  screen({
    title: 'Podaci obrisani',
    html: notice(shared ? 'Ovaj pregledač je izašao iz domaćinstva i obrisao svoje podatke.'
      : 'Spisak, računi, kartice i broj ovog uređaja su obrisani sa servera i iz pregledača.', 'pos') +
      `<a class="btn" href="#/spisak">Počni ispočetka</a>`
  });
}

// ---------- Rute i događaji ----------

const routes = {
  spisak: showList,
  provera: showMatching,
  lokacija: () => showLocation(),
  preporuke: showRecommendation,
  kupovina: showPurchase,
  cene: () => renderSearch(),
  akcije: showSales,
  proizvod: showProduct,
  kartice: showCards,
  vise: showMore,
  vreme: showTripCosts,
  racun: showReceipt,
  ...accountRoutes
};

// Gde je lista bila kad se otišlo na detalje, da Nazad vrati na isto mesto.
const scrolls = {};
let currentRoute = null;
let currentTab = null;

function route() {
  const [path, ...parts] = location.hash.replace(/^#\/?/, '').split('/');
  const arg = parts[0];
  const name = routes[path] ? path : 'spisak';
  if (currentRoute) scrolls[currentRoute] = window.scrollY;
  const currentFrom = currentRoute;
  currentRoute = name;
  const handler = routes[name] || routes.spisak;
  if (name === 'cene' || name === 'akcije') productFrom = '#/' + name;
  if (name === 'vreme' && currentFrom !== 'vreme') tripFrom = currentFrom === 'preporuke' || currentFrom === 'lokacija' ? '#/' + currentFrom : '#/vise';
  if (name === 'akcije' && (currentFrom === 'spisak' || currentFrom === 'cene')) salesFrom = '#/' + currentFrom;
  // Obaveštenje pripada ekranu na kome je nastalo.
  if (name !== currentFrom) { document.getElementById('toast').hidden = true; flushDelete(); }
  const tab = name === 'akcije' || name === 'proizvod' ? 'cene'
    : name === 'racun' || name === 'domacinstvo' || name === 'potrosnja' || name === 'vreme' ? 'vise' : routes[name] ? name : 'spisak';
  document.querySelectorAll('.tabs a').forEach(a => {
    const current = a.dataset.tab === tab;
    if (current) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  const order = ['spisak', 'cene', 'kartice', 'vise'];
  if (name !== currentFrom) {
    const before = order.indexOf(currentTab);
    const after = order.indexOf(tab);
    enterPending = before < 0 || before === after ? 'enter' : after > before ? 'from-r' : 'from-l';
  }
  currentTab = tab;
  window.scrollTo(0, 0);
  const shown = handler(arg ? Number(arg) : undefined, parts);
  // Lista koja se samo ponovo iscrtava (pretraga, akcije) vraća se gde je bila.
  if ((name === 'cene' && search.page) || (name === 'akcije' && sales.page && sales.place === salesPlace())) {
    Promise.resolve(shown).then(() => window.scrollTo(0, scrolls[name] || 0));
  }
}

const actions = {
  retry: () => route(),
  'add-one': () => { listState.adding = 'one'; listState.editing = null; listState.rejected = null; renderList(); },
  'add-many': () => { listState.adding = 'paste'; listState.editing = null; listState.rejected = null; renderList(); },
  'cancel-add': () => { listState.adding = null; listState.rejected = null; renderList(); },
  edit: el => { listState.editing = Number(el.dataset.id); listState.adding = null; renderList(); },
  'cancel-edit': () => { listState.editing = null; renderList(); },
  delete: el => deleteItem(Number(el.dataset.id)),
  merge: el => mergeDuplicates(Number(el.dataset.id)),
  calculate: () => { location.hash = '#/provera'; },
  choose: el => {
    const item = matching.items.find(i => i.itemId === Number(el.dataset.item));
    return resolve(item.itemId, item.candidates[Number(el.dataset.candidate)]);
  },
  reject: el => resolve(Number(el.dataset.item), null),
  flexible: el => useAsFlexible(Number(el.dataset.item)),
  'to-location': () => { location.hash = '#/lokacija'; },
  locate,
  travel: el => { saved.set('walking', el.dataset.value === 'WALKING'); saved.set('nearOnly', null); showLocation(); },
  'near-only': el => { saved.set('nearOnly', el.dataset.value === 'on' ? true : null); showRecommendation(); },
  'use-last': () => { const o = saved.get('origin'); useOrigin(o.lat, o.lng, o.label); },
  'use-place': el => { const p = places.results[Number(el.dataset.index)]; useOrigin(p.latitude, p.longitude, p.name); },
  scenario: el => { selectedType = el.dataset.type; renderRecommendation(); },
  'start-purchase': startPurchase,
  'wrong-product': el => wrongProduct(Number(el.dataset.item)),
  'item-menu': el => {
    const menu = document.getElementById(el.getAttribute('aria-controls'));
    menu.hidden = !menu.hidden;
    el.setAttribute('aria-expanded', String(!menu.hidden));
  },
  'unlocated-more': () => { unlocatedOpen = !unlocatedOpen; renderRecommendation(); },
  'pick-open': el => openPick(Number(el.dataset.item)),
  'pick-cancel': () => { pickTyping.cancel(); picking = null; renderMatching(); },
  pick: el => pickProduct(picking.results[Number(el.dataset.index)]),
  'pick-better': () => pickProduct(picking.better.product, true),
  'pick-keep': () => pickProduct(picking.better.chosen, true),
  'lost-list-ok': () => { saved.set('listLost', null); renderList(); },
  'finish-purchase': () => {
    if (!confirm('Završi kupovinu? Napredak se briše, a spisak ostaje.')) return;
    saved.set('purchase', null);
    saved.set('purchaseDone', true);
    location.hash = '#/spisak';
  },
  'clear-list': () => {
    const count = listState.list.items.length;
    if (!confirm(`Obrisati ceo spisak (${counted(count, 'stavka', 'stavke', 'stavki')})? Ovo ne može da se vrati.`)) return;
    return clearList();
  },
  'start-new-list': clearList,
  'keep-list': () => { saved.set('purchaseDone', null); renderList(); },
  more: () => runSearch(true),
  'add-product': el => addProduct(search.items[Number(el.dataset.index)]),
  'add-current': () => addProduct({ name: window.currentProduct.name, canonicalProductId: window.currentProduct.canonicalProductId }),
  'add-card': () => { cards.adding = true; renderCards(); },
  'cancel-card': () => { cards.adding = false; renderCards(); },
  'show-card': el => { cards.shown = Number(el.dataset.id); renderCards(); },
  'close-card': () => { cards.shown = null; renderCards(); },
  'delete-card': async el => {
    if (!confirm('Obrisati ovu karticu?')) return;
    await api('DELETE', `loyalty-cards/${el.dataset.id}`);
    cards.shown = null;
    await showCards();
  },
  'delete-account': deleteAccount,
  'trip-reset': () => saveTripCosts(null, null, null),
  theme: el => {
    const value = el.dataset.value || null;
    saved.set('theme', value);
    window.applyTheme(value);
    document.querySelectorAll('[data-act="theme"]').forEach(b => b.setAttribute('aria-pressed', String(b === el)));
  },
  ...accountActions,
  'sale-sort': el => { sales.sort = el.dataset.value; return loadSales(); },
  'sale-scope': el => { sales.allChains = el.dataset.value === 'all'; return loadSales(); },
  'sale-category': el => { sales.category = el.dataset.value || null; return loadSales(); },
  'sales-more': () => loadSales(true),
  'sales-locate': salesLocate,
  'add-sale': async el => {
    const p = sales.items[Number(el.dataset.index)];
    await addProduct({ name: p.name, productFamilyId: p.productFamilyId, canonicalProductId: p.canonicalProductId });
    renderSales();
    if (await chainWithoutAddresses(p.retailerCode)) {
      toast(`Na spisku: ${p.name}. ${p.retailerName} ne objavljuje adrese prodavnica, pa ova cena neće ući u plan.`);
    }
  }
};

const forms = {
  ...accountForms,
  'pick-search': data => runPick(String(data.get('q') || '')),
  'trip-costs': data => (view.querySelector('[data-trip-saved]')?.remove(), saveTripCosts(
    tripAmount(data, 'hour', tripCosts.maxValuePerHour),
    tripAmount(data, 'stop', tripCosts.maxCostPerStop),
    tripAmount(data, 'km', tripCosts.maxCostPerKm))),
  'add-one': data => addText(data.get('text')),
  'add-many': data => addText(data.get('text')),
  'edit-item': (data, form) => saveItem(Number(form.dataset.id), data),
  place: async data => {
    places.query = String(data.get('q') || '').trim();
    document.activeElement.blur();
    try {
      places.results = await api('GET', `places?query=${encodeURIComponent(places.query)}`);
    } catch (error) {
      places.results = null;
      return showLocation(error.status === 503 || error.status === 502
        ? 'Pretraga adrese trenutno ne radi. Probaj ponovo za koji trenutak ili koristi trenutnu lokaciju.' : error.message);
    }
    showLocation();
  },
  search: data => { search.query = String(data.get('q') || ''); document.activeElement.blur(); return runSearch(); },
  card: async data => {
    const cardNumber = String(data.get('number')).trim();
    if (data.get('format') === 'EAN_13' && !ean13Valid(cardNumber)) {
      throw fieldError('number', /^\d{13}$/.test(cardNumber)
        ? 'Ovo nije ispravan EAN-13: poslednja (kontrolna) cifra se ne slaže. Proveri broj.'
        : 'EAN-13 ima tačno 13 cifara. Proveri broj ili izaberi „Crtični kod“.');
    }
    await api('POST', 'loyalty-cards', {
      name: String(data.get('name')).trim(),
      cardNumber,
      barcodeFormat: data.get('format')
    });
    cards.adding = false;
    await showCards();
  }
};

/** Greška iz radnje ide u obaveštenje (iz forme u samu formu), dugme se ponovo pušta. */
async function run(button, work, form = null) {
  if (button) button.disabled = true;
  if (form) clearFormError(form);
  try {
    await work();
  } catch (error) {
    if (form && form.isConnected) formError(form, error);
    else toast(error.message, null, null, true);
  } finally {
    if (button && button.isConnected) button.disabled = false;
  }
}

document.addEventListener('click', event => {
  const target = event.target.closest('[data-act]');
  if (!target || (target.dataset.act === 'close-card' && event.target.closest('[data-stop]') && target.classList.contains('overlay'))) return;
  const action = actions[target.dataset.act];
  if (!action) return;
  event.preventDefault();
  run(target.tagName === 'BUTTON' ? target : null, () => action(target));
});

document.addEventListener('change', event => {
  if (event.target.matches('[data-sale-filter]')) {
    search.onSale = event.target.checked;
    runSearch();
    return;
  }
  const box = event.target.closest('[data-check]');
  if (!box) return;
  const purchase = saved.get('purchase');
  purchase.checked[box.dataset.check] = box.checked;
  saved.set('purchase', purchase);
  markPurchase(box, purchase);
});

/** Štikliranje menja samo ono što se promenilo, da kvačica i precrtavanje odigraju animaciju. */
function markPurchase(box, purchase) {
  box.closest('.check').querySelectorAll('.name, .price').forEach(el => el.classList.toggle('strike', box.checked));
  const total = purchaseItems(purchase).length;
  const bought = purchaseBought(purchase);
  const title = view.querySelector('[data-bought]');
  if (title) title.textContent = `Kupljeno ${bought} od ${total}`;
  const bar = view.querySelector('[data-progress]');
  if (bar) bar.style.width = `${total ? bought / total * 100 : 0}%`;
  const store = box.closest('[data-store]');
  const count = store && store.querySelector('[data-store-count]');
  if (count) {
    const boxes = [...store.querySelectorAll('[data-check]')];
    count.textContent = `${boxes.filter(b => b.checked).length}/${boxes.length}`;
  }
}

document.addEventListener('submit', event => {
  const handler = forms[event.target.dataset.form];
  if (!handler) return;
  event.preventDefault();
  run(event.target.querySelector('button:not([type=button])'), () => handler(new FormData(event.target), event.target), event.target);
});

// ---------- Nova verzija ----------

// Kartica otvorena danima, ili stranica iz keša kad mreža nije stigla, vrti
// staru verziju. Kad server ima noviju, ona se učita na sledećem prelazu
// između ekrana, kad nema ničeg nesačuvanog.
const loadedScripts = new URL(document.currentScript.src).search;
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
