'use strict';
// Web verzija za telefone bez Android aplikacije (iPhone). Isti server i isti
// nalog po uređaju kao Android: pregledač pamti nasumičan ključ i šalje ga u
// X-Client-Token. Napredak kupovine ostaje u pregledaču, kao u aplikaciji.

const VERSION = '1.7';
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

// ---------- Server ----------

async function api(method, path, body) {
  let response;
  try {
    response = await fetch('/api/v1/' + path, {
      method,
      headers: {
        'X-Client-Token': clientToken(),
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
      },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
  } catch {
    throw new Error('Nema veze sa serverom. Proveri internet i probaj ponovo.');
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

/** Jedan spisak po uređaju, kao u aplikaciji; nov ako je stari nestao. */
async function loadList() {
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

function number(value, decimals) {
  const [whole, part] = Math.abs(value).toFixed(decimals).split('.');
  return (value < 0 ? '−' : '') + whole.replace(/\B(?=(\d{3})+(?!\d))/g, '.') + (part ? ',' + part : '');
}
const money = value => number(value, 2) + ' RSD';
const dinars = value => number(Math.round(value), 0);
function decimal(value, max = 2) {
  const text = number(value, max);
  return text.includes(',') ? text.replace(/0+$/, '').replace(/,$/, '') : text;
}
function date(iso) {
  const [y, m, d] = String(iso).slice(0, 10).split('-');
  return `${d}.${m}.${y}.`;
}
const shortDate = iso => date(iso).slice(0, 6);
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
  chevron: '<svg viewBox="0 0 24 24"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg>'
};

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

let listState = { list: null, adding: null };

async function showList() {
  screen({ title: 'Moj spisak', html: spinner('Učitavam spisak…') });
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
       </div>` + form +
      (items.length
        ? `<div class="section">Stavke na spisku ${pill(items.length)}</div>` + items.map(item => `
          <div class="card">
            <div class="row">
              <div class="grow">
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
  const field = view.querySelector('[data-form] input, [data-form] textarea');
  if (field) field.focus();
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
      <button class="card row" style="text-align:left;cursor:pointer" data-act="locate">
        <span class="letter" style="border-radius:50%;background:var(--mint);color:var(--on-mint)">${icon.pin}</span>
        <span class="grow"><span class="name" style="display:block">Koristi trenutnu lokaciju</span>
        <span class="muted small">Safari će pitati za dozvolu</span></span>${icon.chevron}
      </button>
      ${last ? `<button class="card row" style="text-align:left;cursor:pointer" data-act="use-last">
        <span class="grow"><span class="label">Poslednja polazna tačka</span><span style="display:block">${coordinates(last.lat, last.lng)}</span></span>
        <span class="btn">Koristi</span></button>` : ''}
      ${message ? notice(message, tone) : ''}
      <p class="muted small">Lokacija služi samo za ovo računanje i za redosled pretrage. Ne prati se u pozadini, a server je ne čuva.</p>
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

function useOrigin(lat, lng) {
  saved.set('origin', { lat, lng });
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

function mapsUrl(origin, stores) {
  const stops = stores.map(s => `${s.latitude},${s.longitude}`).filter((v, i, all) => all.indexOf(v) === i);
  let url = 'https://www.google.com/maps/dir/?api=1';
  if (origin) url += `&origin=${origin.lat},${origin.lng}`;
  url += `&destination=${stops[stops.length - 1]}`;
  if (stops.length > 1) url += `&waypoints=${stops.slice(0, -1).join('%7C')}`;
  return url + '&travelmode=driving';
}

async function showRecommendation() {
  const origin = saved.get('origin');
  const listId = saved.get('listId');
  if (!origin || !listId) { location.hash = '#/lokacija'; return; }
  screen({ title: 'Preporuke', back: '#/lokacija', html: spinner('Računam tri scenarija…') });
  try {
    recommendation = await api('GET', `shopping-lists/${listId}/recommendations?latitude=${origin.lat}&longitude=${origin.lng}`);
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
        <div class="grow"><div class="label accent">Polazna tačka</div><div>${coordinates(origin.lat, origin.lng)}</div></div>
        <a class="btn" href="#/lokacija">Promeni</a>
      </div>
      <div class="scenarios">${scenarios.map(x => `
        <button class="scenario" aria-pressed="${x.type === s.type}" data-act="scenario" data-type="${x.type}" ${x.available ? '' : 'disabled'}>
          <span class="small muted">${scenarioShort[x.type]}</span>
          <span class="price">${x.available && x.totalCost != null ? dinars(x.totalCost) : 'nema'}</span>
          <span class="small muted">ukupno</span>
          ${x.available ? `<span class="small muted">korpa ${dinars(x.basketCost)}</span>` : ''}
        </button>`).join('')}
      </div>
      <div class="card stripe">
        <div class="row" style="flex-wrap:wrap;gap:8px">${pill(scenarioBadge[s.type], 'pos')}
          ${s.available && s.savingsComparedWithSingleStore > 0 ? pill(`Ušteda ${dinars(s.savingsComparedWithSingleStore)} RSD`, 'pos') : ''}</div>
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
        <a class="btn" href="${esc(mapsUrl(origin, stores))}" target="_blank" rel="noopener">${icon.route}${stores.length === 1 ? 'Pregled puta do prodavnice' : `Pregled rute kroz ${stores.length} ${plural(stores.length, 'prodavnicu', 'prodavnice', 'prodavnica')}`}</a>
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
          s.approximateRoute ? 'Udaljenosti su procena vazdušnom linijom, pravi put je obično duži.' : null,
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
  saved.set('purchase', { startedAt: new Date().toISOString(), scenario: s, checked: {} });
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
          <div style="text-align:right"><div class="small muted">planirano</div><div class="price accent">${dinars(s.basketCost)} RSD</div></div>
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
            <a class="icon-action" style="color:var(--primary)" href="${esc(mapsUrl(null, [store]))}" target="_blank" rel="noopener" aria-label="Put do prodavnice ${esc(store.retailerName)}">${icon.route}</a>
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

let search = { query: '', page: null, items: [], error: null };

function searchLocation() {
  const origin = saved.get('origin');
  // Zaokruženo na ~1 km, kao u aplikaciji: dovoljno za redosled lanaca.
  return origin ? `&latitude=${origin.lat.toFixed(2)}&longitude=${origin.lng.toFixed(2)}` : '';
}

function renderSearch(loading = false) {
  const page = search.page;
  screen({
    title: 'Cene',
    html: `
      <form data-form="search" role="search">
        <input name="q" type="search" enterkeyhint="search" placeholder="Naziv ili barkod, npr. kravica mleko" value="${esc(search.query)}" autocomplete="off">
      </form>
      <p class="muted small">Cena u cenovniku nije potvrda da proizvoda ima na stanju.</p>` +
      (search.error ? notice(search.error, 'err') : '') +
      (page && page.correctedQuery ? `<p class="small muted">Prikazujem rezultate za „${esc(page.correctedQuery)}“.</p>` : '') +
      (page && !search.items.length ? `<div class="card soft center">Nema proizvoda za „${esc(search.query)}“.</div>` : '') +
      search.items.map((p, index) => `
        <div class="card">
          <div class="name">${esc(p.name)}</div>
          <div class="muted small">${esc([p.brand, p.quantityValue && p.baseUnit ? amountLabel(p.quantityValue, p.baseUnit, p.packageCount) : null].filter(Boolean).join(' · '))}</div>
          ${(p.availability || []).slice(0, 3).map(a => `<div class="row small"><span class="grow">${esc(a.retailerName)}</span>
            <span class="muted">${shortDate(a.latestPriceDate)}</span>
            <b>${a.minimumEffectivePrice != null ? 'od ' + money(a.minimumEffectivePrice) : 'bez cene'}</b></div>`).join('')}
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
    const page = await api('GET', `products/search?query=${encodeURIComponent(search.query.trim())}&page=${next}&limit=10${searchLocation()}`);
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
  await api('POST', `shopping-lists/${listState.list.id}/items`, {
    name: product.name,
    canonicalProductId: product.productFamilyId ? null : product.canonicalProductId,
    productFamilyId: product.productFamilyId || null,
    quantity: 1,
    matchingRule: product.productFamilyId ? 'PRODUCT_FAMILY' : 'EXACT_PRODUCT'
  });
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

async function showProduct(id) {
  screen({ title: 'Cene proizvoda', back: '#/cene', html: spinner('Učitavam ponude…') });
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
    back: '#/cene',
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
        return `<div class="card ${cheapest ? 'selected' : ''}"><div class="row top-align">
          <span class="letter">${esc(o.retailerName.trim().charAt(0).toUpperCase())}</span>
          <div class="grow"><div class="name">${esc(o.retailerName)}</div>
            <div class="muted small">${esc(offerScope(o))} · cene od ${shortDate(o.priceDate)}</div>
            ${o.discountedPrice != null && o.regularPrice != null && o.discountedPrice < o.regularPrice ? `<div class="small" style="color:var(--on-amber)">Akcija, redovno ${money(o.regularPrice)}</div>` : ''}
            ${isCaseOf(o, product) ? `<div class="small" style="color:var(--on-amber)">Pakovanje od ${o.packageCount / single} kom · ${money(o.effectivePrice * single / o.packageCount)} po komadu</div>` : ''}
            ${o.priceNeedsCheck ? `<div class="muted small">Manje od pola uobičajene cene u drugim lancima</div>${pill('Proveri cenu', 'warn')}` : ''}
            ${cheapest ? pill('Najbolja cena', 'pos') : ''}</div>
          <div style="text-align:right"><div class="price ${cheapest ? 'accent' : ''}" style="font-size:18px">${money(o.effectivePrice)}</div>
            ${unitPriceLabel(o, product) ? `<div class="muted small">${unitPriceLabel(o, product)}</div>` : ''}</div>
        </div></div>`;
      }).join('') : notice('Za ovaj proizvod još nema važećih cena.')) +
      (product.priceHistory.length ? `<div class="section">Poslednje promene cena</div><div class="card flush">${product.priceHistory.map(p => `
        <div class="list-row row"><div class="grow"><div>${esc(p.retailerName)}</div>
        <div class="muted small">${esc([date(p.priceDate), p.storeName, p.storeFormatName].filter(Boolean).join(' · '))}</div></div>
        <span class="price">${money(p.effectivePrice)}</span></div>`).join('')}</div>` : '')
  });
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

function showMore() {
  const standalone = navigator.standalone || matchMedia('(display-mode: standalone)').matches;
  screen({
    title: 'Više',
    html:
      (standalone ? '' : `<div class="card hero">
        <div class="title">Dodaj na početni ekran</div>
        <p>U Safari-ju dodirni <b>Podeli</b> pa <b>Dodaj na početni ekran</b>. Otvara se kao aplikacija, a spisak i kartice ostaju i kad Safari čisti stare podatke sajtova.</p>
      </div>`) +
      `<div class="card">
        <div class="title">O aplikaciji</div>
        <p>Pametna kupovina ${VERSION}, web verzija</p>
        <p class="muted small">Cene su iz zvaničnih cenovnika trgovaca. Merodavna je cena u prodavnici.</p>
        <p class="muted small">Web verzija nema skeniranje računa i barkoda, obaveštenja o pojeftinjenju ni prijavu preko Google-a — to ima Android aplikacija.</p>
        <div class="btn-row"><a class="btn text" href="/privatnost">Politika privatnosti</a><a class="btn text" href="/uslovi">Uslovi korišćenja</a></div>
      </div>
      <div class="card">
        <div class="name">Broj uređaja (za pitanja o podacima)</div>
        <p class="muted small" style="overflow-wrap:anywhere">${esc(clientToken())}</p>
        <button class="btn danger" data-act="delete-account">Obriši moje podatke</button>
      </div>`
  });
}

async function deleteAccount() {
  if (!confirm('Obrisati spisak, kartice i sve podatke ovog uređaja sa servera? Ovo se ne može vratiti.')) return;
  await api('DELETE', 'accounts/me');
  try { Object.keys(localStorage).filter(k => k.startsWith('pk.')).forEach(k => localStorage.removeItem(k)); } catch { /* nema čega */ }
  // Bez odlaska na spisak: on bi odmah napravio nov nalog.
  screen({
    title: 'Podaci obrisani',
    html: notice('Spisak, kartice i broj ovog uređaja su obrisani sa servera i iz pregledača.', 'pos') +
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
  proizvod: showProduct,
  kartice: showCards,
  vise: showMore
};

function route() {
  const [name, arg] = location.hash.replace(/^#\/?/, '').split('/');
  const handler = routes[name] || routes.spisak;
  document.querySelectorAll('.tabs a').forEach(a => {
    const current = a.dataset.tab === (routes[name] ? name : 'spisak');
    if (current) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
  window.scrollTo(0, 0);
  handler(arg ? Number(arg) : undefined);
}

const actions = {
  retry: () => route(),
  'add-one': () => { listState.adding = 'one'; renderList(); },
  'add-many': () => { listState.adding = 'paste'; renderList(); },
  'cancel-add': () => { listState.adding = null; renderList(); },
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
  'use-last': () => { const o = saved.get('origin'); useOrigin(o.lat, o.lng); },
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
  'delete-account': deleteAccount
};

const forms = {
  'add-one': data => addText(data.get('text')),
  'add-many': data => addText(data.get('text')),
  coordinates: data => {
    const lat = Number(String(data.get('lat')).replace(',', '.'));
    const lng = Number(String(data.get('lng')).replace(',', '.'));
    if (!(Math.abs(lat) <= 90 && Math.abs(lng) <= 180) || Number.isNaN(lat) || Number.isNaN(lng)) {
      return showLocation('Koordinate nisu ispravne. Primer: 44.8170 i 20.4930.');
    }
    useOrigin(lat, lng);
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
  run(event.target.querySelector('button:not([type=button])'), () => handler(new FormData(event.target)));
});

window.addEventListener('hashchange', route);
route();
