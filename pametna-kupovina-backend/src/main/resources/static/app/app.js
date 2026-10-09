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
    saved.set('listId', null);
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

async function api(method, path, body, retried = false) {
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
      body: body === undefined || body instanceof FormData ? body : JSON.stringify(body)
    });
  } catch {
    throw new Error('Nema veze sa serverom. Proveri internet i probaj ponovo.');
  }
  if (response.status === 401 && !retried
      && (response.headers.get('WWW-Authenticate') || '').startsWith('Bearer')) {
    const session = saved.get('session');
    if (session?.access === token) saved.set('session', { ...session, expiresAt: 0 });
    return api(method, path, body, true);
  }
  if (!response.ok) {
    let message = null;
    try { message = (await response.json()).message; } catch { /* nije JSON */ }
    const error = new Error(message || (response.status === 429
      ? 'Previše zahteva odjednom. Sačekaj minut pa probaj ponovo.'
      : 'Server trenutno ne odgovara. Probaj ponovo.'));
    error.status = response.status;
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

async function loadListOnce() {
  const id = saved.get('listId');
  if (id) {
    try {
      return await api('GET', `shopping-lists/${id}`);
    } catch (error) {
      if (error.status !== 404) throw error;
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
  const minutes = Math.floor((seconds + 30) / 60);
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}
const perUnit = unit => unit === 'g' ? 'kg' : unit === 'ml' ? 'l' : 'kom';

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);
const pill = (text, tone = '') => `<span class="pill ${tone}">${esc(text)}</span>`;
const notice = (text, tone = '') => `<div class="notice ${tone}">${esc(text)}</div>`;
const spinner = text => `<div class="spinner"></div><p class="center muted">${esc(text)}</p>`;
const icon = {
  add: '<svg viewBox="0 0 24 24"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  paste: '<svg viewBox="0 0 24 24"><path d="M19 2h-4.18C14.4.84 13.3 0 12 0S9.6.84 9.18 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm7 18H5V4h2v3h10V4h2v16z"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
  pin: '<svg viewBox="0 0 24 24"><path d="M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3A8.99 8.99 0 0 0 13 3.06V1h-2v2.06A8.99 8.99 0 0 0 3.06 11H1v2h2.06A8.99 8.99 0 0 0 11 20.94V23h2v-2.06A8.99 8.99 0 0 0 20.94 13H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"/></svg>',
  route: '<svg viewBox="0 0 24 24"><path d="M21.71 11.29l-9-9a1 1 0 0 0-1.42 0l-9 9a1 1 0 0 0 0 1.42l9 9a1 1 0 0 0 1.42 0l9-9a1 1 0 0 0 0-1.42zM14 14.5V12h-4v3H8v-4c0-.55.45-1 1-1h5V7.5l3.5 3.5-3.5 3.5z"/></svg>',
  card: '<svg viewBox="0 0 24 24"><path d="M20 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6c0-1.11-.89-2-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z"/></svg>',
  tag: '<svg viewBox="0 0 24 24"><path d="M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z"/></svg>',
  chevron: '<svg viewBox="0 0 24 24"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg>'
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
  document.title = `${title} · Pametna kupovina`;
}

let toastTimer = null;
function toast(text, undoLabel = null, onUndo = null) {
  const box = document.getElementById('toast');
  box.innerHTML = `<span>${esc(text)}</span>` + (undoLabel ? `<button type="button">${esc(undoLabel)}</button>` : '');
  box.hidden = false;
  if (onUndo) box.querySelector('button').onclick = () => { box.hidden = true; onUndo(); };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.hidden = true; }, 5000);
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

let listState = { list: null, adding: null, editing: null };

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
  const unit = storedUnit(c.requiredBaseUnit, c.targetQuantity) || 'kg';
  const amount = c.targetQuantity ? inputNumber(c.targetQuantity / editUnits[unit][1]) : '';
  return `<form class="card" data-form="edit-item" data-id="${item.id}">
    <div class="label accent">Izmeni stavku</div>
    ${flexible ? `
      <label class="label" for="edit-name">Šta kupuješ</label>
      <input id="edit-name" name="name" type="text" value="${esc(item.name)}" autocomplete="off" required>
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
  if (!(quantity > 0)) throw new Error('Upiši broj veći od nule.');
  const flexible = item.matchingRule === 'FLEXIBLE_CATEGORY';
  let name = item.name;
  let constraints = item.flexibleConstraints;
  if (flexible) {
    name = String(data.get('name') || '').trim();
    if (!name) throw new Error('Upiši šta kupuješ.');
    const c = item.flexibleConstraints || {};
    const amountText = String(data.get('amount') || '').trim();
    const amount = parseNumber(amountText);
    if (amountText && !(amount > 0)) throw new Error('Količina nije ispravna. Primer: 1,5.');
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

function renderList() {
  const items = listState.list.items;
  const purchase = saved.get('purchase');
  const form = listState.adding === 'one'
    ? `<form class="card" data-form="add-one">
         <label class="label" for="one">Šta kupuješ</label>
         <input id="one" name="text" type="text" autocomplete="off" enterkeyhint="done" placeholder="npr. mleko 2l ili jaja 10 kom" required>
         <div class="btn-row"><button type="button" class="btn" data-act="cancel-add">Odustani</button><button class="btn primary">Dodaj</button></div>
       </form>`
    : listState.adding === 'paste'
      ? `<form class="card" data-form="add-many">
           <label class="label" for="many">Nalepi spisak iz poruke ili beleške</label>
           <textarea id="many" name="text" placeholder="mleko&#10;hleb&#10;ćevapi 3kg&#10;2x pivo" required></textarea>
           <div class="btn-row"><button type="button" class="btn" data-act="cancel-add">Odustani</button><button class="btn primary">Dodaj sve</button></div>
         </form>`
      : '';
  screen({
    title: 'Moj spisak',
    subtitle: items.length ? counted(items.length, 'stavka', 'stavke', 'stavki') : null,
    html:
      (purchase ? `<a class="card row" href="#/kupovina" style="text-decoration:none;color:inherit">
          <div class="grow"><div class="name">Kupovina u toku</div>
          <div class="muted small">Kupljeno ${purchaseBought(purchase)} od ${purchase.scenario.items.length}</div></div>${icon.chevron}</a>` : '') +
      `<div class="btn-row">
         <button class="btn primary" data-act="add-one">${icon.add}Dodaj stavku</button>
         <button class="btn" data-act="add-many">${icon.paste}Nalepi spisak</button>
       </div>` + form + (listState.adding ? '' : salesShortcut()) +
      (items.length
        ? `<div class="section">Stavke na spisku ${pill(items.length)}</div>` + items.map(item => item.id === listState.editing ? itemEditor(item) : `
          <div class="card">
            <div class="row">
              <div class="grow" data-act="edit" data-id="${item.id}" role="button" tabindex="0" style="cursor:pointer" aria-label="Izmeni ${esc(item.name)}">
                ${itemAttention(item)}
                <div class="name">${esc(item.name)}</div>
                ${itemRule(item) ? `<div class="muted small">${esc(itemRule(item))}</div>` : ''}
              </div>
              <span class="amount">${esc(itemAmount(item))}</span>
              <button class="icon-action" data-act="delete" data-id="${item.id}" aria-label="Obriši ${esc(item.name)}">${icon.trash}</button>
            </div>
          </div>`).join('')
        : `<div class="card soft center"><div class="title">Spisak je prazan</div>
           <p class="muted">Nalepi spisak iz poruke ili beleške, ili dodaj stavku po stavku.</p></div>`),
    action: `<button class="btn primary main" data-act="calculate" ${items.length ? '' : 'disabled'}>Izračunaj</button>`
  });
  const field = view.querySelector('[data-form] input:not([type=hidden]), [data-form] textarea');
  if (field && !listState.editing) field.focus();
  const editing = view.querySelector('[data-form=edit-item]');
  if (editing) editing.scrollIntoView({ block: 'nearest' });
}

async function addText(text) {
  const listId = listState.list.id;
  const result = await api('POST', `shopping-lists/${listId}/items/paste`, { text });
  listState.adding = null;
  listState.list = await api('GET', `shopping-lists/${listId}`);
  renderList();
  toast(result.createdCount === 1 ? 'Dodato.' : `Dodato: ${counted(result.createdCount, 'stavka', 'stavke', 'stavki')}.`);
}

async function deleteItem(id) {
  const listId = listState.list.id;
  const item = listState.list.items.find(i => i.id === id);
  await api('DELETE', `shopping-lists/${listId}/items/${id}`);
  listState.list.items = listState.list.items.filter(i => i.id !== id);
  renderList();
  toast(`Obrisano: ${item.name}`, 'Vrati', async () => {
    await api('POST', `shopping-lists/${listId}/items`, {
      name: item.name,
      rawInput: item.rawInput,
      barcode: item.barcode,
      canonicalProductId: item.matchingRule === 'EXACT_PRODUCT' ? item.matchedCanonicalProductId : null,
      productFamilyId: item.matchingRule === 'PRODUCT_FAMILY' ? item.matchedProductFamilyId : null,
      quantity: item.quantity,
      matchingRule: item.matchingRule,
      flexibleConstraints: item.flexibleConstraints
    });
    listState.list = await api('GET', `shopping-lists/${listId}`);
    renderList();
  });
}

// ---------- Provera proizvoda (korak 1) ----------

let matching = null;

const needsDecision = item => item.blocksOptimization ||
  item.matchingStatus === 'NEEDS_CONFIRMATION' || item.matchingStatus === 'UNMATCHED';
const canUseAsFlexible = item => item.matchingRule === 'EXACT_PRODUCT' && item.matchingStatus === 'UNMATCHED';
const statusText = { PENDING: 'Čeka proveru', AUTO_MATCHED: 'Automatski', NEEDS_CONFIRMATION: 'Treba potvrda', CONFIRMED: 'Potvrđeno', UNMATCHED: 'Nije pronađeno' };
const statusTone = { PENDING: 'warn', AUTO_MATCHED: 'pos', NEEDS_CONFIRMATION: 'warn', CONFIRMED: 'pos', UNMATCHED: 'err' };

async function showMatching() {
  screen({ title: 'Provera proizvoda', back: '#/spisak', html: spinner('Tražim odgovarajuće proizvode…') });
  try {
    const listId = saved.get('listId');
    if (!listId) { location.hash = '#/spisak'; return; }
    matching = await api('POST', `shopping-lists/${listId}/matching?includeProductsWithoutBarcode=true`);
  } catch (error) {
    return failed('Provera proizvoda', error, '#/provera');
  }
  renderMatching();
}

function renderMatching(errorText = null) {
  const decide = matching.items.filter(needsDecision);
  const settled = matching.items.filter(i => !needsDecision(i));
  const connected = matching.automaticallyMatchedItems + (matching.confirmedItems || 0);
  const pending = matching.blockingItemIds.length || (matching.itemsNeedingConfirmation + matching.unmatchedItems);
  const share = matching.totalItems ? Math.round(connected / matching.totalItems * 100) : 0;
  screen({
    title: 'Provera proizvoda',
    back: '#/spisak',
    html: `
      <div class="card soft">
        <div class="label accent">Korak 1 od 3</div>
        <div class="title">${decide.length ? `${decide.length} ${plural(decide.length, 'stavka traži', 'stavke traže', 'stavki traži')} tvoju odluku` : 'Sve stavke su prepoznate'}</div>
        <p class="muted small">Potvrdi nejasne stavke da bismo našli najpovoljnije cene u blizini.</p>
        <div class="row small"><span class="grow muted">Prepoznato ${connected} od ${matching.totalItems}</span>${decide.length ? `<b class="label accent">još ${decide.length}</b>` : ''}</div>
        <div class="progress"><span style="width:${share}%"></span></div>
      </div>` +
      (errorText ? notice(errorText, 'err') : '') +
      (decide.length ? `<div class="section">Treba tvoja odluka ${pill(decide.length)}</div>` : '') +
      decide.map((item, index) => `
        <div class="card">
          <div class="row top-align">
            <div class="grow"><div class="label">Sa spiska</div><div class="title">„${esc(item.requestedName)}“</div></div>
            ${pill(statusText[item.matchingStatus], statusTone[item.matchingStatus])}
          </div>
          ${index === 0 || decide[index - 1].explanation !== item.explanation ? `<p class="muted small">${esc(item.explanation)}</p>` : ''}
          ${item.matchingStatus === 'NEEDS_CONFIRMATION' ? item.candidates.slice(0, 5).map((c, ci) => `
            <button class="card soft" style="text-align:left;cursor:pointer" data-act="choose" data-item="${item.itemId}" data-candidate="${ci}">
              <div class="row"><div class="grow">
                <div>${esc(c.name)}</div>
                <div class="row small muted">${pill(Math.round(c.score.totalScore * 100) + '%', 'pos')}
                  ${esc([c.brand, c.quantityValue && c.baseUnit ? amountLabel(c.quantityValue, c.baseUnit, c.packageCount) : null].filter(Boolean).join(' · '))}</div>
              </div><b class="label accent">Izaberi</b></div>
            </button>`).join('') : ''}
          ${canUseAsFlexible(item) ? `
            <button class="card soft" style="text-align:left;cursor:pointer" data-act="flexible" data-item="${item.itemId}">
              <div class="name" style="color:var(--primary)">Neka aplikacija izabere najpovoljnije</div>
              <div class="muted small">Kad ti nije važan brend ni pakovanje.</div>
            </button>` : ''}
          ${item.matchingStatus === 'NEEDS_CONFIRMATION' ? `<button class="btn text" data-act="reject" data-item="${item.itemId}">Nijedan, ostavi neupareno</button>` : ''}
        </div>`).join('') +
      (settled.length ? `<div class="section">Prepoznato ${pill(settled.length)}</div>
        <div class="card flush">${settled.map(item => `
          <div class="list-row"><div class="name">✓ ${esc(item.requestedName)}</div>
          ${item.matchingStatus === 'AUTO_MATCHED' ? `<div class="muted small">${esc(item.explanation)}</div>` : ''}</div>`).join('')}
        </div>` : ''),
    action: `<button class="btn primary main" data-act="to-location" ${matching.readyForOptimization ? '' : 'disabled'}>${
      matching.readyForOptimization ? 'Nastavi na lokaciju'
        : pending > 0 ? 'Odluči još za ' + counted(pending, 'stavku', 'stavke', 'stavki') : 'Potvrdi označene stavke'}</button>`
  });
}

async function resolve(itemId, candidate) {
  const listId = matching.listId;
  await api('PUT', `shopping-lists/${listId}/items/${itemId}/match`, {
    action: candidate ? 'CONFIRM' : 'REJECT',
    canonicalProductId: candidate ? candidate.canonicalProductId : null,
    productFamilyId: candidate && !candidate.canonicalProductId ? candidate.productFamilyId : null,
    note: candidate ? 'Korisnik je potvrdio kandidata u web verziji.' : 'Korisnik je izabrao opciju neupareno u web verziji.'
  });
  matching = await api('POST', `shopping-lists/${listId}/matching?includeProductsWithoutBarcode=true`);
  renderMatching();
}

async function useAsFlexible(itemId) {
  const listId = matching.listId;
  const list = await api('GET', `shopping-lists/${listId}`);
  const item = list.items.find(i => i.id === itemId);
  await api('PUT', `shopping-lists/${listId}/items/${itemId}`, {
    name: item.name,
    rawInput: item.rawInput,
    quantity: item.quantity,
    matchingRule: 'FLEXIBLE_CATEGORY',
    flexibleConstraints: { category: item.name.trim() }
  });
  matching = await api('POST', `shopping-lists/${listId}/matching?includeProductsWithoutBarcode=true`);
  renderMatching();
}

// ---------- Polazna tačka (korak 2) ----------

const coordinates = (lat, lng) => `${lat.toFixed(4)}, ${lng.toFixed(4)}`;
const originLabel = origin => origin.label || coordinates(origin.lat, origin.lng);

// Adrese iz pretrage ostaju na ekranu dok se ne izabere jedna.
let places = { query: '', results: null };

function showLocation(message = null, tone = 'err') {
  const last = saved.get('origin');
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
      ${places.results ? (places.results.length ? `<div class="card flush">${places.results.map((p, index) => `
        <button class="list-row row" style="width:100%;text-align:left;cursor:pointer;background:none;border:0" data-act="use-place" data-index="${index}">
          <span style="color:var(--primary)">${icon.pin}</span><span class="grow">${esc(p.name)}</span>${icon.chevron}</button>`).join('')}</div>`
        : notice('Ne nalazimo tu adresu. Dodaj grad ili probaj sa nazivom kraja.', 'warn')) : ''}
      <button class="card row" style="text-align:left;cursor:pointer" data-act="locate">
        <span class="letter" style="border-radius:50%;background:var(--mint);color:var(--on-mint)">${icon.pin}</span>
        <span class="grow"><span class="name" style="display:block">Koristi trenutnu lokaciju</span>
        <span class="muted small">Safari će pitati za dozvolu</span></span>${icon.chevron}
      </button>
      ${last ? `<button class="card row" style="text-align:left;cursor:pointer" data-act="use-last">
        <span class="grow"><span class="label">Poslednja polazna tačka</span><span style="display:block">${esc(originLabel(last))}</span></span>
        <span class="btn">Koristi</span></button>` : ''}
      ${message ? notice(message, tone) : ''}
      <p class="muted small">Lokacija služi samo za ovo računanje i za redosled pretrage. Ne prati se u pozadini, a server je ne čuva. Adresu server traži preko OpenStreetMap-a i ne upisuje je.</p>
      <details class="card">
        <summary>Unesi koordinate ručno</summary>
        <form data-form="coordinates">
          <input name="lat" inputmode="decimal" placeholder="Geografska širina, npr. 44.8170" required>
          <input name="lng" inputmode="decimal" placeholder="Geografska dužina, npr. 20.4930" required>
          <p class="muted small">Koordinate dobijaš u Google mapama kad dugo pritisneš tačku na mapi.</p>
          <button class="btn primary">Prikaži preporuke</button>
        </form>
      </details>`
  });
}

function useOrigin(lat, lng, label = null) {
  saved.set('origin', label ? { lat, lng, label } : { lat, lng });
  places = { query: '', results: null };
  location.hash = '#/preporuke';
}

function locate() {
  if (!navigator.geolocation) return showLocation('Ovaj pregledač ne daje lokaciju. Upiši koordinate ručno.');
  showLocation('Tražim lokaciju…', '');
  navigator.geolocation.getCurrentPosition(
    position => useOrigin(position.coords.latitude, position.coords.longitude),
    error => showLocation(error.code === 1
      ? 'Safari nema dozvolu za lokaciju. Dozvoli je za ovaj sajt (Podešavanja → Aplikacije → Safari → Lokacija) ili upiši koordinate ručno.'
      : 'Lokacija trenutno nije dostupna. Probaj ponovo ili upiši koordinate ručno.'),
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 300000 }
  );
}

// ---------- Preporuke (korak 3) ----------

let recommendation = null;
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
  const missing = s.items.length - s.coveredItems;
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
  screen({ title: 'Preporuke', back: '#/lokacija', html: spinner('Računam tri scenarija…') });
  try {
    const travel = saved.get('walking', false) ? '&travelMode=WALKING' : '';
    recommendation = await api('GET', `shopping-lists/${listId}/recommendations?latitude=${origin.lat}&longitude=${origin.lng}${travel}`);
  } catch (error) {
    return failed('Preporuke', error, '#/preporuke');
  }
  renderRecommendation();
}

function renderRecommendation() {
  const result = recommendation;
  const origin = saved.get('origin');
  const scenarios = distinctScenarios(result);
  const s = scenarios.find(x => x.type === selectedType) || result.recommendedBalance;
  const stores = [...s.stores].sort((a, b) => a.stopOrder - b.stopOrder);
  const unresolved = s.items.filter(i => i.resultStatus !== 'AVAILABLE');
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
          ${x.available ? `<span class="small muted">korpa ${whole(x.basketCost)}</span>` : ''}
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
      </div>` +
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
              ${manySmallPacks(item) ? pill('Mnogo malih pakovanja', 'warn') : ''}</div>
            <span class="price">${item.lineTotal != null ? money(item.lineTotal) : 'nema'}</span></div>`).join('')}
        </div>`;
      }).join('') +
      (unresolved.length ? `<div class="section">Bez ponude ${pill(unresolved.length)}</div>` + unresolved.map(item => `
        <div class="card"><div class="row top-align"><div class="grow name">${esc(item.requestedName)}</div>${pill(unresolvedStatus[item.resultStatus], 'warn')}</div>
        <p class="muted small">${esc(item.explanation)}</p></div>`).join('') : '') +
      (result.unlocatedPriceOptions.length ? `<div class="section">Lanci bez poznate adrese</div>` +
        notice('Ne znamo u kojoj prodavnici važi ova cena, pa ovi lanci nisu u planu.', 'warn') +
        result.unlocatedPriceOptions.map(o => `<div class="card"><div class="row"><div class="grow name">${esc(o.retailerName)}</div>
          <span class="price">${Math.abs(o.highestBasketCost - o.lowestBasketCost) < 0.005 ? money(o.lowestBasketCost) : `${number(o.lowestBasketCost, 2)} – ${money(o.highestBasketCost)}`}</span></div>
          <p class="muted small">${o.coveredItems} od ${o.totalItems} stavki · ${esc(o.caveat)}</p></div>`).join('') : '') +
      `<details class="card"><summary>Kako je računato</summary>
        ${[
          s.dataAsOf ? `Cene su iz cenovnika od ${date(s.dataAsOf)}; cenovnik ne govori da li je artikal na polici.` : 'Za ovaj scenario nema važećih cena.',
          s.disclaimer || result.disclaimer,
          s.available ? `Korpa ${money(s.basketCost)}, put ${money(s.travelCost)}, vreme ${money(s.timeCost)}, stajanja ${money(s.stopCost)}.` : null,
          `Put računamo ${money(a.costPerKm)} po kilometru, vreme ${money(a.valuePerHour)} po satu i ${money(a.costPerStop)} po stajanju.`,
          s.approximateRoute ? 'Udaljenosti su procena: vazdušna linija uvećana za ulice, bez saobraćaja.' : null,
          `Razmotreno ${counted(result.candidateStoreCount, 'prodavnica', 'prodavnice', 'prodavnica')} u krugu od ${decimal(a.candidateRadiusMeters / 1000, 1)} km.`
        ].filter(Boolean).map(line => `<p class="small">${esc(line)}</p>`).join('')}
      </details>`,
    action: s.available ? `<button class="btn primary main" data-act="start-purchase">Započni kupovinu po ovom planu</button>` : ''
  });
}

// ---------- Kupovina ----------

const purchaseBought = purchase => Object.values(purchase.checked).filter(Boolean).length;

function startPurchase() {
  const s = distinctScenarios(recommendation).find(x => x.type === selectedType) || recommendation.recommendedBalance;
  if (saved.get('purchase') && !confirm('Započni novu kupovinu? Napredak prethodne kupovine se briše.')) return;
  saved.set('purchase', { startedAt: new Date().toISOString(), scenario: s, checked: {},
    walking: recommendation.assumptions?.travelMode === 'WALKING' });
  location.hash = '#/kupovina';
}

function showPurchase() {
  const purchase = saved.get('purchase');
  if (!purchase) { location.hash = '#/spisak'; return; }
  const s = purchase.scenario;
  const total = s.items.length;
  const bought = purchaseBought(purchase);
  const stores = [...s.stores].sort((a, b) => a.stopOrder - b.stopOrder);
  const row = item => {
    const done = Boolean(purchase.checked[item.itemId]);
    return `<label class="check">
      <input type="checkbox" data-check="${item.itemId}" ${done ? 'checked' : ''} aria-label="Kupljeno: ${esc(item.requestedName)}">
      <span class="grow"><span class="name ${done ? 'strike' : ''}" style="display:block">${esc(item.requestedName)}</span>
        ${item.productName && item.productName.toLowerCase() !== item.requestedName.toLowerCase() ? `<span class="muted small" style="display:block">${esc(item.productName)}</span>` : ''}
        ${quantityLine(item) ? `<span class="muted small" style="display:block">${esc(quantityLine(item))}</span>` : ''}
        ${item.storeId == null ? `<span class="small" style="display:block;color:var(--error)">${esc(item.explanation)}</span>` : ''}</span>
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
          <div class="grow"><div class="label">Napredak kupovine</div><div class="title">Kupljeno ${bought} od ${total}</div></div>
          <div style="text-align:right"><div class="small muted">planirano</div><div class="price accent">${whole(s.basketCost)} ${market.currency}</div></div>
        </div>
        <div class="progress"><span style="width:${total ? bought / total * 100 : 0}%"></span></div>
        <p class="muted small">Cene u sačuvanom planu se ne osvežavaju.</p>
        <a class="card soft row" href="#/kartice" style="text-decoration:none;color:inherit;padding:10px 12px">
          <span style="color:var(--primary)">${icon.card}</span><span class="grow">Lojalti kartice za kasu — prikaži barkod</span>${icon.chevron}</a>
      </div>` +
      (!s.complete ? notice('Plan nije potpun. Stavke bez ponude su na dnu.', 'warn') : '') +
      stores.map(store => {
        const items = s.items.filter(i => i.storeId === store.storeId);
        const done = items.filter(i => purchase.checked[i.itemId]).length;
        return `<div class="card flush">
          <div class="row" style="padding:12px 8px 12px 16px">
            <span class="badge">${store.stopOrder}</span>
            <div class="grow"><div class="name">${esc(store.retailerName)}</div><div class="muted small">${esc(storeAddress(store))}</div></div>
            <b>${done}/${items.length}</b>
            <a class="icon-action" style="color:var(--primary)" href="${esc(mapsUrl(null, [store], purchase.walking))}" target="_blank" rel="noopener" aria-label="Put do prodavnice ${esc(store.retailerName)}">${icon.route}</a>
          </div>
          ${items.map(row).join('')}
        </div>`;
      }).join('') +
      (unresolved.length ? `<div class="section">Bez prodavnice ${pill(unresolved.length)}</div><div class="card flush">${unresolved.map(row).join('')}</div>` : '') +
      `<button class="btn" data-act="finish-purchase">Završi kupovinu</button>
       <p class="muted small">Napredak kupovine čuva ovaj pregledač; originalni spisak ostaje nepromenjen.</p>`
  });
}

// ---------- Cene proizvoda ----------

let search = { query: '', onSale: false, page: null, items: [], error: null };

function searchLocation() {
  const origin = saved.get('origin');
  // Zaokruženo na ~1 km, kao u aplikaciji: dovoljno za redosled lanaca.
  return origin ? `&latitude=${origin.lat.toFixed(2)}&longitude=${origin.lng.toFixed(2)}` : '';
}

/** Tri najjeftinija lanca, a lanac na akciji uvek među njima, kao u aplikaciji. */
function shortList(offers) {
  const first = offers.slice(0, 3);
  const onSale = a => a.discountPercent && !a.priceNeedsCheck;
  const sale = offers.find(onSale);
  return first.some(onSale) || !sale ? first : [...first.slice(0, 2), sale];
}

function renderSearch(loading = false) {
  const page = search.page;
  screen({
    title: 'Cene',
    html: `
      <form data-form="search" role="search">
        <input name="q" type="search" enterkeyhint="search" placeholder="Naziv ili barkod, npr. kravica mleko" value="${esc(search.query)}" autocomplete="off">
      </form>
      <label class="toggle"><input type="checkbox" data-sale-filter ${search.onSale ? 'checked' : ''}>Samo proizvodi na akciji</label>
      <p class="muted small">Cena u cenovniku nije potvrda da proizvoda ima na stanju.</p>` +
      (!page && !loading ? salesShortcut() : '') +
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
      (page && page.hasNext && !loading ? `<button class="btn" data-act="more">Prikaži još</button>` : '')
  });
}

async function runSearch(more = false) {
  if (!search.query.trim()) return renderSearch();
  const next = more ? search.page.page + 1 : 0;
  if (!more) search.items = [];
  search.error = null;
  renderSearch(true);
  try {
    const page = await api('GET', `products/search?query=${encodeURIComponent(search.query.trim())}&page=${next}&limit=10${
      search.onSale ? '&onSale=true' : ''}${searchLocation()}`);
    search.page = page;
    search.items = more ? search.items.concat(page.items) : page.items;
  } catch (error) {
    search.error = error.message;
  }
  renderSearch();
  const field = view.querySelector('input[name=q]');
  if (!more && field && document.activeElement === document.body) field.blur();
}

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
    (a.priceNeedsCheck - b.priceNeedsCheck) || (isCaseOf(a, product) - isCaseOf(b, product)) || (a.effectivePrice - b.effectivePrice));
  const comparable = offers.filter(o => !o.priceNeedsCheck && !isCaseOf(o, product));
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
        const cheapest = index === 0 && offers.length > 1 && !o.priceNeedsCheck && !isCaseOf(o, product);
        const single = product.packageCount;
        const off = o.priceNeedsCheck ? null : discountPercent(o.regularPrice, o.effectivePrice);
        return `<div class="card ${cheapest ? 'selected' : ''} ${off ? tier(off) : ''}"><div class="row top-align">
          <span class="letter">${esc(o.retailerName.trim().charAt(0).toUpperCase())}</span>
          <div class="grow"><div class="name">${esc(o.retailerName)}</div>
            <div class="muted small">${esc(offerScope(o))} · cene od ${shortDate(o.priceDate)}</div>
            ${off ? saleLine(off, o.regularPrice, o.saleEndDate) : ''}
            ${isCaseOf(o, product) ? `<div class="small" style="color:var(--on-amber)">Pakovanje od ${o.packageCount / single} kom · ${money(o.effectivePrice * single / o.packageCount)} po komadu</div>` : ''}
            ${o.priceNeedsCheck ? `<div class="muted small">Manje od pola uobičajene cene u drugim lancima</div>${pill('Proveri cenu', 'warn')}` : ''}
            ${cheapest ? pill('Najbolja cena', 'pos') : ''}</div>
          <div style="text-align:right"><div class="price ${off ? 'sale-price' : cheapest ? 'accent' : ''}" style="font-size:18px">${money(o.effectivePrice)}</div>
            ${unitPriceLabel(o, product) ? `<div class="muted small">${unitPriceLabel(o, product)}</div>` : ''}</div>
        </div></div>`;
      }).join('') : notice('Za ovaj proizvod još nema važećih cena.')) +
      (product.priceHistory.length ? `<div class="section">Poslednje promene cena</div><div class="card flush">${product.priceHistory.map(p => `
        <div class="list-row row"><div class="grow"><div>${esc(p.retailerName)}</div>
        <div class="muted small">${esc([date(p.priceDate), p.storeName, p.storeFormatName].filter(Boolean).join(' · '))}</div></div>
        <span class="price">${money(p.effectivePrice)}</span></div>`).join('')}</div>` : '')
  });
}

// ---------- Akcije ----------

let sales = { sort: 'DISCOUNT', category: null, place: null, page: null, items: [], categories: [], error: null };
const saleSorts = { DISCOUNT: 'Najveći popust', SAVING: 'Najveća ušteda', PRICE: 'Najniža cena' };

/** Lista se pamti dok se ide na detalje i nazad; nova kad se promeni mesto. */
function showSales() {
  if (sales.page && sales.place === searchLocation()) return renderSales();
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
    loadList().then(list => { listState.list = list; if (location.hash === '#/akcije' && sales.page) renderSales(); }).catch(() => {});
  }
  sales.error = null;
  sales.place = searchLocation();
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
        <span class="muted small">Safari će pitati za dozvolu</span></span>${icon.chevron}</button>` : '') +
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
}

function salesLocate() {
  if (!navigator.geolocation) return toast('Ovaj pregledač ne daje lokaciju.');
  navigator.geolocation.getCurrentPosition(
    position => { saved.set('origin', { lat: position.coords.latitude, lng: position.coords.longitude }); loadSales(); },
    error => toast(error.code === 1 ? 'Safari nema dozvolu za lokaciju.' : 'Lokacija trenutno nije dostupna.'),
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
          <input name="name" type="text" placeholder="Naziv, npr. Moj Maxi" required>
          <input name="number" inputmode="text" placeholder="Broj sa kartice" required>
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
      (standalone ? '' : `<div class="card hero">
        <div class="title">Dodaj na početni ekran</div>
        <p>U Safari-ju dodirni <b>Podeli</b> pa <b>Dodaj na početni ekran</b>. Otvara se kao aplikacija, a spisak i kartice ostaju i kad Safari čisti stare podatke sajtova.</p>
      </div>`) +
      accountCard() +
      `<a class="card row" href="#/racun" style="text-decoration:none;color:inherit">
        <span class="grow"><span class="name" style="display:block">Dodaj račun</span>
        <span class="muted small">Screenshot ili PDF digitalnog računa</span></span>${icon.chevron}</a>` +
      link('#/potrosnja', 'Potrošnja', 'Koliko trošiš mesečno, po prodavnicama i kategorijama') +
      link('#/domacinstvo', 'Domaćinstvo', account.state?.household ? 'Delite spisak, račune i kartice' : 'Pozovi ukućanina da delite spisak') +
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
  racun: showReceipt,
  ...accountRoutes
};

// Gde je lista bila kad se otišlo na detalje, da Nazad vrati na isto mesto.
const scrolls = {};
let currentRoute = null;

function route() {
  const [path, ...parts] = location.hash.replace(/^#\/?/, '').split('/');
  const arg = parts[0];
  const name = routes[path] ? path : 'spisak';
  if (currentRoute) scrolls[currentRoute] = window.scrollY;
  const currentFrom = currentRoute;
  currentRoute = name;
  const handler = routes[name] || routes.spisak;
  if (name === 'cene' || name === 'akcije') productFrom = '#/' + name;
  if (name === 'akcije' && (currentFrom === 'spisak' || currentFrom === 'cene')) salesFrom = '#/' + currentFrom;
  // Obaveštenje pripada ekranu na kome je nastalo.
  if (name !== currentFrom) document.getElementById('toast').hidden = true;
  const tab = name === 'akcije' || name === 'proizvod' ? 'cene'
    : name === 'racun' || name === 'domacinstvo' || name === 'potrosnja' ? 'vise' : routes[name] ? name : 'spisak';
  document.querySelectorAll('.tabs a').forEach(a => {
    const current = a.dataset.tab === tab;
    if (current) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  window.scrollTo(0, 0);
  const shown = handler(arg ? Number(arg) : undefined, parts);
  // Lista koja se samo ponovo iscrtava (pretraga, akcije) vraća se gde je bila.
  if ((name === 'cene' && search.page) || (name === 'akcije' && sales.page && sales.place === searchLocation())) {
    Promise.resolve(shown).then(() => window.scrollTo(0, scrolls[name] || 0));
  }
}

const actions = {
  retry: () => route(),
  'add-one': () => { listState.adding = 'one'; listState.editing = null; renderList(); },
  'add-many': () => { listState.adding = 'paste'; listState.editing = null; renderList(); },
  'cancel-add': () => { listState.adding = null; renderList(); },
  edit: el => { listState.editing = Number(el.dataset.id); listState.adding = null; renderList(); },
  'cancel-edit': () => { listState.editing = null; renderList(); },
  delete: el => deleteItem(Number(el.dataset.id)),
  calculate: () => { location.hash = '#/provera'; },
  choose: el => {
    const item = matching.items.find(i => i.itemId === Number(el.dataset.item));
    return resolve(item.itemId, item.candidates[Number(el.dataset.candidate)]);
  },
  reject: el => resolve(Number(el.dataset.item), null),
  flexible: el => useAsFlexible(Number(el.dataset.item)),
  'to-location': () => { location.hash = '#/lokacija'; },
  locate,
  travel: el => { saved.set('walking', el.dataset.value === 'WALKING'); showLocation(); },
  'use-last': () => { const o = saved.get('origin'); useOrigin(o.lat, o.lng, o.label); },
  'use-place': el => { const p = places.results[Number(el.dataset.index)]; useOrigin(p.latitude, p.longitude, p.name); },
  scenario: el => { selectedType = el.dataset.type; renderRecommendation(); },
  'start-purchase': startPurchase,
  'finish-purchase': () => {
    if (!confirm('Završi kupovinu? Napredak se briše, a spisak ostaje.')) return;
    saved.set('purchase', null);
    location.hash = '#/spisak';
  },
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
  ...accountActions,
  'sale-sort': el => { sales.sort = el.dataset.value; return loadSales(); },
  'sale-category': el => { sales.category = el.dataset.value || null; return loadSales(); },
  'sales-more': () => loadSales(true),
  'sales-locate': salesLocate,
  'add-sale': el => {
    const p = sales.items[Number(el.dataset.index)];
    return addProduct({ name: p.name, productFamilyId: p.productFamilyId, canonicalProductId: p.canonicalProductId })
        .then(() => renderSales());
  }
};

const forms = {
  ...accountForms,
  'add-one': data => addText(data.get('text')),
  'add-many': data => addText(data.get('text')),
  'edit-item': (data, form) => saveItem(Number(form.dataset.id), data),
  coordinates: data => {
    const lat = Number(String(data.get('lat')).replace(',', '.'));
    const lng = Number(String(data.get('lng')).replace(',', '.'));
    if (!(Math.abs(lat) <= 90 && Math.abs(lng) <= 180) || Number.isNaN(lat) || Number.isNaN(lng)) {
      return showLocation('Koordinate nisu ispravne. Primer: 44.8170 i 20.4930.');
    }
    useOrigin(lat, lng);
  },
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
    await api('POST', 'loyalty-cards', {
      name: String(data.get('name')).trim(),
      cardNumber: String(data.get('number')).trim(),
      barcodeFormat: data.get('format')
    });
    cards.adding = false;
    await showCards();
  }
};

/** Greška iz radnje ide u obaveštenje, dugme se ponovo pušta. */
async function run(button, work) {
  if (button) button.disabled = true;
  try {
    await work();
  } catch (error) {
    toast(error.message);
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
  showPurchase();
});

document.addEventListener('submit', event => {
  const handler = forms[event.target.dataset.form];
  if (!handler) return;
  event.preventDefault();
  run(event.target.querySelector('button:not([type=button])'), () => handler(new FormData(event.target), event.target));
});

window.addEventListener('hashchange', route);
route();
// Bez mreže u prodavnici stranica se ipak otvori (sw.js).
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
refreshMarket().then(changed => { if (changed) route(); });
