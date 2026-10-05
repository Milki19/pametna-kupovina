'use strict';
// Nalog, domaćinstvo, telefoni na nalogu i potrošnja u web verziji. Učitava
// se pre app.js, koji dodaje ove ekrane i radnje u svoje; zato se ovde ništa
// iz app.js ne poziva dok se ekran ne otvori.

// ---------- Nalog na ekranu Više ----------

let account = { state: null, devices: [], error: null };

const standaloneApp = () => navigator.standalone || matchMedia('(display-mode: standalone)').matches;

/** Stanje naloga i telefoni; Više se iscrtava odmah, a ovo stiže posle. */
async function loadAccount() {
  try {
    account.state = await api('GET', 'accounts/me');
    account.devices = account.state.signedIn || account.state.household
      ? await api('GET', 'accounts/devices') : [];
    account.error = null;
  } catch (error) {
    account.error = error.message;
  }
}

function deviceName(device, index) {
  const name = device.name && device.name.trim() ? device.name.trim() : `Telefon ${index + 1}`;
  return device.current ? `${name} (ovaj)` : name;
}

function accountCard() {
  const state = account.state;
  if (account.error) return `<div class="card">${notice(account.error, 'err')}</div>`;
  if (!state) return `<div class="card"><div class="title">Nalog</div><p class="muted small">Učitavam…</p></div>`;
  return `<div class="card">
    <div class="title">Nalog</div>
    <p>${state.signedIn
      ? 'Prijavljen si preko Google-a, pa ćeš spiskove naći i na drugom telefonu.'
      : 'Spiskovi su vezani za ovaj pregledač. Prijavi se da ih nađeš i na drugom telefonu.'}</p>
    ${state.signedIn ? '' : `<button class="btn primary" data-act="google-sign-in">Prijavi se preko Google-a</button>
      <div id="google-button" class="center"></div>`}
    ${account.devices.length > 1 ? `<div class="name">Telefoni na nalogu</div>
      <div class="card flush">${account.devices.map((device, index) => `<div class="list-row row">
        <div class="grow"><div>${esc(deviceName(device, index))}</div>
          <div class="muted small">poslednji put ${date(localIsoDate(device.lastSeenAt))}</div></div>
        ${device.current ? '' : `<button class="btn" data-act="remove-device" data-id="${device.id}" data-name="${esc(deviceName(device, index))}">Ukloni</button>`}
      </div>`).join('')}</div>` : ''}
    ${state.signedIn || state.household ? `<button class="btn" data-act="sign-out">Odjavi se</button>` : ''}
  </div>`;
}

/** Google-ovo dugme se učitava tek kad ga kupac zatraži. */
async function googleSignIn(button) {
  const { clientId } = await api('GET', 'accounts/sign-in/google');
  if (!clientId) throw new Error('Prijava preko Google-a još nije podešena na serveru.');
  if (!window.google?.accounts?.id) {
    await new Promise((done, fail) => {
      const script = document.createElement('script');
      script.src = 'https://accounts.google.com/gsi/client';
      script.async = true;
      script.onload = done;
      script.onerror = () => fail(new Error('Google se ne učitava. Proveri internet i probaj ponovo.'));
      document.head.appendChild(script);
    });
  }
  google.accounts.id.initialize({
    client_id: clientId,
    callback: response => run(null, () => finishGoogleSignIn(response.credential)),
    ux_mode: 'popup',
    use_fedcm_for_button: true
  });
  const box = document.getElementById('google-button');
  if (!box) return;
  if (button) button.hidden = true;
  google.accounts.id.renderButton(box, { type: 'standard', theme: 'outline', size: 'large', text: 'signin_with', locale: 'sr' });
  box.insertAdjacentHTML('beforeend', '<p class="muted small">Dodirni Google-ovo dugme da izabereš nalog.</p>');
}

async function finishGoogleSignIn(idToken) {
  if (!idToken) throw new Error('Prijava nije uspela. Probaj ponovo kasnije.');
  account.state = await api('POST', 'accounts/sign-in/google', { idToken });
  // Isti čovek već ima nalog sa drugog telefona: ovaj pregledač prelazi na
  // njega, a spisak ovog pregledača ide za njim.
  if (location.hash === '#/vise') showMore();
  toast('Prijavljen si. Spiskovi su sada vezani za nalog.');
}

function clearBrowser() {
  try { Object.keys(localStorage).filter(k => k.startsWith('pk.')).forEach(k => localStorage.removeItem(k)); } catch { /* nema čega */ }
}

async function signOut() {
  const state = account.state || {};
  if (!confirm(state.signedIn
    ? 'Odjaviti se? Ovaj pregledač više ne vidi spisak, račune ni kartice. Vraćaju se kad se ponovo prijaviš.'
    : 'Izaći iz domaćinstva? Zajednički spisak, računi i kartice ostaju ukućanima, a ovde počinješ ispočetka.')) return;
  await api('DELETE', 'sessions/current');
  clearBrowser();
  account = { state: null, devices: [], error: null };
  // Bez odlaska na spisak: on bi odmah otvorio nov nalog.
  screen({
    title: 'Odjavljen si',
    html: notice(state.signedIn ? 'Odjavljen si. Prijavi se ponovo da vidiš svoje spiskove.' : 'Izašao si iz domaćinstva.', 'pos') +
      `<a class="btn" href="#/spisak">Počni ispočetka</a>`
  });
}

async function removeDevice(el) {
  if (!confirm(`Ukloniti telefon „${el.dataset.name}“? Taj telefon se odjavljuje sa naloga i više ne vidi spisak, račune ni kartice. Na njemu počinje ispočetka.`)) return;
  await api('DELETE', `accounts/devices/${el.dataset.id}`);
  showMore();
  toast('Telefon je uklonjen.');
}

// ---------- Domaćinstvo ----------

let household = { link: null, validMinutes: 15, error: null };
const HOUSEHOLD_PREFIX = 'pametnakupovina:domacinstvo:';

const householdLink = (code, listId) => `${location.origin}/app/#/domacinstvo/${encodeURIComponent(code)}/${listId}`;

/** Kod i spisak iz linka ili iz starog Android QR koda; null za bilo šta drugo. */
function parseHousehold(text) {
  const value = String(text || '').trim();
  const match = value.match(/#\/domacinstvo\/([A-Za-z0-9_-]+)\/(\d+)/)
    || (value.startsWith(HOUSEHOLD_PREFIX) && value.slice(HOUSEHOLD_PREFIX.length).match(/^([A-Za-z0-9_-]+):(\d+)$/));
  return match ? { code: match[1], listId: Number(match[2]) } : null;
}

function showHousehold(_, parts = []) {
  const [code, listId] = parts;
  if (code && Number(listId) > 0) return showJoin(code, Number(listId));
  const link = household.link;
  screen({
    title: 'Domaćinstvo',
    back: '#/vise',
    html: `<p>Ukućani dele isti spisak, račune i kartice. Kad se neko pridruži, sve što je imao na svom telefonu prelazi u zajedničko.</p>` +
      (household.error ? notice(household.error, 'err') : '') +
      (link ? `<div class="card">
          <div class="code-box household">${qrSvg(link, 'QR kod za pridruživanje domaćinstvu') || ''}</div>
          <p class="center"><b>Važi ${household.validMinutes} minuta</b></p>
          <p class="muted small">Neka ukućanin kamerom telefona skenira ovaj kod ili otvori link koji mu pošalješ. U Android aplikaciji: Meni → Domaćinstvo → Pridruži se.</p>
          <div class="btn-row"><button class="btn" data-act="household-invite">Novi kod</button>
            <button class="btn primary" data-act="household-share">Pošalji link</button></div>
        </div>`
        : `<button class="btn primary" data-act="household-invite">Pozovi ukućanina</button>`) +
      `<form class="card" data-form="household-join">
        <div class="title">Pridruži se</div>
        <label class="label" for="household-link">Link koji ti je ukućanin poslao</label>
        <input id="household-link" name="link" type="text" inputmode="url" autocomplete="off" placeholder="https://…/app/#/domacinstvo/…" required>
        <button class="btn primary">Nastavi</button>
      </form>`
  });
}

async function createInvite() {
  household.error = null;
  const list = await loadList();
  listState.list = list;
  const invite = await api('POST', 'accounts/invite');
  household.link = householdLink(invite.code, list.id);
  household.validMinutes = invite.validMinutes || 15;
  showHousehold();
}

async function shareInvite() {
  const link = household.link;
  if (navigator.share) {
    try {
      await navigator.share({ title: 'Pametna kupovina', text: 'Pridruži se mom spisku u Pametnoj kupovini:', url: link });
      return;
    } catch (error) {
      if (error.name === 'AbortError') return;
    }
  }
  try {
    await navigator.clipboard.writeText(link);
    toast('Link je kopiran. Pošalji ga ukućaninu.');
  } catch {
    prompt('Kopiraj link i pošalji ga ukućaninu:', link);
  }
}

function showJoin(code, listId) {
  screen({
    title: 'Domaćinstvo',
    back: '#/vise',
    html: `<div class="card">
        <div class="title">Pridruži se domaćinstvu?</div>
        <p>Ovaj telefon ulazi u zajednički nalog. Ono što je na tvom spisku dodaje se zajedničkom, a računi i kartice postaju zajednički.</p>
        ${standaloneApp() ? '' : `<p class="muted small">Ako Pametnu kupovinu otvaraš sa početnog ekrana, kopiraj ovaj link i nalepi ga tamo u Više → Domaćinstvo. Safari i aplikacija sa početnog ekrana ne dele podatke.</p>`}
        <div class="btn-row"><a class="btn" href="#/spisak">Odustani</a>
          <button class="btn primary" data-act="household-join" data-code="${esc(code)}" data-list="${listId}">Pridruži se</button></div>
      </div>`
  });
}

/** Kao u Android aplikaciji: posle ulaska ovaj telefon radi na zajedničkom spisku. */
async function joinHousehold(code, listId) {
  const own = await loadList();
  const state = await api('POST', 'accounts/join', { code });
  if (state?.market) { market = state.market; separators = separatorsOf(market.locale); saved.set('market', market); }
  let shared;
  try {
    shared = await api('GET', `shopping-lists/${listId}`);
  } catch (error) {
    if (error.status !== 404) throw error;
  }
  if (shared && shared.id !== own.id) {
    for (const item of own.items) {
      await api('POST', `shopping-lists/${shared.id}/items`, {
        name: item.name,
        rawInput: item.rawInput,
        barcode: item.barcode,
        canonicalProductId: item.matchingRule === 'EXACT_PRODUCT' ? item.matchedCanonicalProductId : null,
        productFamilyId: item.matchingRule === 'PRODUCT_FAMILY' ? item.matchedProductFamilyId : null,
        quantity: item.quantity,
        matchingRule: item.matchingRule,
        flexibleConstraints: item.flexibleConstraints
      });
    }
    await api('DELETE', `shopping-lists/${own.id}`).catch(() => {});
    saved.set('listId', shared.id);
  }
  household = { link: null, validMinutes: 15, error: null };
  account.state = null;
  // Nazad ne vodi ponovo na isti kod, koji je sada iskorišćen.
  location.replace('#/spisak');
  toast(shared ? 'Sada delite spisak, račune i kartice.' : 'Sada delite račune i kartice. Zajednički spisak nije pronađen, pa ostaje tvoj.');
}

// ---------- Potrošnja ----------

let spending = { month: null, data: null, receipts: null, habits: null, view: 'receipts', error: null };

function monthKey(instant) {
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone: market.timeZone, year: 'numeric', month: '2-digit' })
      .format(new Date(instant)).slice(0, 7);
  } catch { return String(instant).slice(0, 7); }
}
/** Dan u vremenskoj zoni tržišta, kao 2026-10-05. */
function localIsoDate(instant) {
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone: market.timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(instant));
  } catch { return String(instant).slice(0, 10); }
}
function monthName(key, withYear = true) {
  const [y, m] = key.split('-').map(Number);
  try {
    return new Intl.DateTimeFormat(market.locale, { month: 'long', ...(withYear ? { year: 'numeric' } : {}), timeZone: 'UTC' })
      .format(new Date(Date.UTC(y, m - 1, 1))).replace(/\.$/, '');
  } catch { return key; }
}
function shiftMonth(key, by) {
  const [y, m] = key.split('-').map(Number);
  const d = new Date(Date.UTC(y, m - 1 + by, 1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`;
}
function localDateTime(instant) {
  try {
    return new Intl.DateTimeFormat(market.locale, { timeZone: market.timeZone, day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' })
      .format(new Date(instant));
  } catch { return date(instant); }
}

async function showSpending() {
  if (!spending.month) spending.month = monthKey(Date.now());
  screen({ title: 'Potrošnja', back: '#/vise', html: spinner('Učitavam račune…') });
  try {
    [spending.data, spending.receipts, spending.habits] = await Promise.all([
      api('GET', `receipts/spending?month=${spending.month}-01`),
      api('GET', 'receipts?limit=200'),
      api('GET', 'receipts/habits?limit=20')
    ]);
    spending.error = null;
  } catch (error) {
    return failed('Potrošnja', error, '#/potrosnja');
  }
  renderSpending();
}

function bars(rows, label, value) {
  const top = Math.max(...rows.map(value), 0);
  return rows.map(row => `<div class="bar-row">
    <div class="row small"><span class="grow">${esc(label(row))}</span><b>${money(value(row))}</b></div>
    <div class="progress"><span style="width:${top > 0 ? value(row) / top * 100 : 0}%"></span></div></div>`).join('');
}

const receiptItems = r => !r.itemsRead
  ? `<p class="muted small">Stavke još nisu pročitane sa sajta Poreske uprave.</p>`
  : r.items == null || (!r.items.length && !r.itemsLoaded) ? `<p class="muted small">Učitavam stavke…</p>`
  : r.items.length ? r.items.map(i => `<div class="row small"><span class="grow">${esc(i.name)}</span>
      <span class="muted">${decimal(Number(i.quantity), 3)} ×</span><span>${money(Number(i.totalPrice))}</span></div>`).join('')
  : `<p class="muted small">Račun nema stavke.</p>`;

/** Spisak računa dolazi bez stavki; stavke jednog računa stižu kad se otvori. */
document.addEventListener('toggle', async event => {
  const box = event.target;
  if (!box.matches?.('details[data-receipt]') || !box.open) return;
  const receipt = spending.receipts?.find(r => r.id === Number(box.dataset.receipt));
  if (!receipt || !receipt.itemsRead || receipt.itemsLoaded) return;
  try {
    const full = await api('GET', `receipts/${receipt.id}`);
    Object.assign(receipt, full, { itemsLoaded: true });
  } catch (error) {
    box.querySelector('.receipt-items').innerHTML = notice(error.message, 'err');
    return;
  }
  box.querySelector('.receipt-items').innerHTML = receiptItems(receipt);
}, true);

function renderSpending() {
  const key = spending.month;
  const months = spending.data.byMonth;
  const thisMonth = months.find(m => String(m.month).slice(0, 7) === key);
  const lastMonth = months.find(m => String(m.month).slice(0, 7) === shiftMonth(key, -1));
  const receipts = spending.receipts.filter(r => monthKey(r.issuedAt) === key);
  const spent = thisMonth ? Number(thisMonth.spent) : 0;
  const count = thisMonth ? thisMonth.receipts : 0;
  const shops = new Set(receipts.map(r => r.shopName).filter(Boolean)).size;
  const change = lastMonth && Number(lastMonth.spent) > 0 && thisMonth
    ? Math.round((spent / Number(lastMonth.spent) - 1) * 100) : null;
  const current = monthKey(Date.now());
  const weeks = spending.data.byWeek;
  const tabs = { receipts: 'Računi', shops: 'Prodavnice', categories: 'Kategorije', habits: 'Navike' };
  const scan = typeof routes !== 'undefined' && routes.racun
    ? `<a class="btn primary" href="#/racun">${icon.add}Dodaj račun</a>` : '';
  const views = {
    receipts: () => receipts.length ? `<div class="card flush">${receipts.map(r => `<details class="list-row" data-receipt="${r.id}">
        <summary class="row"><span class="grow"><span class="name" style="display:block">${esc(r.shopName || 'Prodavnica')}</span>
          <span class="muted small">${esc(localDateTime(r.issuedAt))}</span></span><b>${money(Number(r.totalAmount))}</b></summary>
        <div class="receipt-items">${receiptItems(r)}</div>
      </details>`).join('')}</div>` : `<div class="card soft center">Nema računa za ovaj mesec.</div>`,
    shops: () => spending.data.byShop.length ? `<div class="card">${bars(spending.data.byShop,
        s => `${s.shopName || 'Prodavnica'} · ${counted(s.receipts, 'račun', 'računa', 'računa')}`, s => Number(s.spent))}</div>`
      : `<div class="card soft center">Još nema podataka o prodavnicama.</div>`,
    categories: () => spending.data.byCategory.length ? `<div class="card">${bars(spending.data.byCategory,
        c => c.category || 'Ostalo', c => Number(c.spent))}</div>`
      : `<div class="card soft center">Kategorije se vide kad skeniraš račune sa stavkama.</div>`,
    habits: () => spending.habits.length ? `<div class="card flush">${spending.habits.map(h => `<div class="list-row row">
        <span class="grow"><span style="display:block">${esc(h.name)}</span><span class="muted small">poslednji put ${date(localIsoDate(h.lastBought))}</span></span>
        <b>${counted(h.times, 'put', 'puta', 'puta')}</b></div>`).join('')}</div>`
      : `<div class="card soft center">Navike se vide kad skeniraš račune sa stavkama.</div>`
  };
  screen({
    title: 'Potrošnja',
    back: '#/vise',
    html: `<div class="row">
        <button class="icon-action" data-act="spending-month" data-by="-1" aria-label="Prethodni mesec" style="transform:scaleX(-1)">${icon.chevron}</button>
        <div class="grow center title" style="text-transform:capitalize">${esc(monthName(key))}</div>
        <button class="icon-action" data-act="spending-month" data-by="1" aria-label="Sledeći mesec" ${key >= current ? 'disabled' : ''}>${icon.chevron}</button>
      </div>
      <div class="card hero">
        <div class="label">Ukupna mesečna potrošnja</div>
        <div class="big">${money(spent)}</div>
        ${change != null ? `<p class="small">${change >= 0 ? '+' : '−'}${Math.abs(change)}% u odnosu na ${esc(monthName(shiftMonth(key, -1), false))}</p>` : ''}
        <div class="stats">
          <div><span class="small">Računa</span><b>${count}</b></div>
          <div><span class="small">Prodavnica</span><b>${shops}</b></div>
          <div><span class="small">Prosečna korpa</span><b>${count ? whole(spent / count) : '–'}</b></div>
        </div>
      </div>` + scan +
      (weeks.length ? `<div class="section">Nedeljni pregled potrošnje</div><div class="card">${bars(weeks,
        w => `${w.bucket + 1}. nedelja`, w => Number(w.spent))}</div>` : '') +
      `<div class="section">Analitika troškova</div>
      <div class="chips" role="group" aria-label="Prikaz">${Object.entries(tabs).map(([value, label]) =>
        `<button class="chip" data-act="spending-view" data-value="${value}" aria-pressed="${value === spending.view}">${label}</button>`).join('')}</div>` +
      views[spending.view]() +
      (spending.receipts.length ? '' : `<p class="muted small">Potrošnja se puni iz računa koje zavedeš: skeniraj QR sa računa u Android aplikaciji${scan ? ' ili dodaj snimak ekrana ili fajl digitalnog računa' : ''}.</p>`)
  });
}

// ---------- Za app.js ----------

const accountRoutes = {
  domacinstvo: showHousehold,
  potrosnja: showSpending
};

const accountActions = {
  'google-sign-in': el => googleSignIn(el),
  'sign-out': signOut,
  'remove-device': removeDevice,
  'household-invite': () => createInvite().catch(error => { household.error = error.message; showHousehold(); }),
  'household-share': shareInvite,
  'household-join': el => joinHousehold(el.dataset.code, Number(el.dataset.list)),
  'spending-month': el => { spending.month = shiftMonth(spending.month, Number(el.dataset.by)); return showSpending(); },
  'spending-view': el => { spending.view = el.dataset.value; renderSpending(); }
};

const accountForms = {
  'household-join': data => {
    const parsed = parseHousehold(data.get('link'));
    if (!parsed) throw new Error('To nije link za domaćinstvo. Zatraži od ukućanina novi.');
    location.hash = `#/domacinstvo/${encodeURIComponent(parsed.code)}/${parsed.listId}`;
  }
};
