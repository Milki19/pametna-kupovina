// Kupovina po planu: štikliranje, preskakanje i „Poništi“.

import { date, esc, market, money, notice, pill, whole } from '../core/format.js';
import { icon } from '../core/html.js';
import { screen, toast, view } from '../core/screen.js';
import { purchaseBought, purchaseItems } from '../core/shared.js';
import { saved } from '../core/storage.js';
import { distinctScenarios, mapsUrl, onTheWay, quantityLine, recommendation, scenarioTitle, selectedType, storeAddress } from './plan.js';

/** Kupuje se samo ono što ima prodavnicu u planu; stavke bez nje se ne broje. */
/** Gde stavka stoji: gore ono što još treba, dole sklopljeno kupljeno i preskočeno. */
const purchaseGroup = (purchase, item) =>
  purchase.checked[item.itemId] ? 'bought' : purchase.skipped?.[item.itemId] ? 'skipped' : 'active';
/** Koji sklopljeni odeljci su otvoreni; samo dok je stranica otvorena. */
const purchaseOpen = {};
let purchaseRedraw = null;

function startPurchase() {
  const s = distinctScenarios(recommendation).find(x => x.type === selectedType) || recommendation.recommendedBalance;
  if (saved.get('purchase') && !confirm('Započni novu kupovinu? Napredak prethodne kupovine se briše.')) return;
  saved.set('purchase', { startedAt: new Date().toISOString(), listId: saved.get('listId'), scenario: s, checked: {}, skipped: {},
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
    const skipped = purchaseGroup(purchase, item) === 'skipped';
    // Bez prodavnice nema šta da se štiklira: stavka samo kaže zašto je nema.
    if (item.storeId == null) return `<div class="list-row"><span class="name" style="display:block">${esc(item.requestedName)}</span>
      <span class="small ${onTheWay(s, item) ? 'muted' : ''}" style="display:block;${onTheWay(s, item) ? '' : 'color:var(--error)'}">${esc(item.explanation)}</span></div>`;
    // Ceo red je kućica; „Preskoči" je pored, ne u meniju.
    return `<div class="purchase-row"><label class="check">
      <input type="checkbox" data-check="${item.itemId}" ${done ? 'checked' : ''} aria-label="Kupljeno: ${esc(item.requestedName)}">
      <span class="grow"><span class="name ${done ? 'strike' : ''}" style="display:block">${esc(item.requestedName)}</span>
        ${item.productName && item.productName.toLowerCase() !== item.requestedName.toLowerCase() ? `<span class="muted small" style="display:block">${esc(item.productName)}</span>` : ''}
        ${quantityLine(item) ? `<span class="muted small" style="display:block">${esc(quantityLine(item))}</span>` : ''}
        ${item.substituteNote ? `<span class="small" style="display:block;color:var(--on-amber)">${esc(item.substituteNote)}</span>` : ''}
        ${item.storeId == null ? `<span class="small ${onTheWay(s, item) ? 'muted' : ''}" style="display:block;${onTheWay(s, item) ? '' : 'color:var(--error)'}">${esc(item.explanation)}</span>` : ''}</span>
      ${item.lineTotal != null ? `<span class="price ${done ? 'strike' : ''}">${money(item.lineTotal)}</span>` : ''}
    </label>${done ? '' : skipped
      ? `<button type="button" class="check-action" data-act="unskip-item" data-item="${item.itemId}" aria-label="Vrati u kupovinu: ${esc(item.requestedName)}">Vrati</button>`
      : `<button type="button" class="check-action" data-act="skip-item" data-item="${item.itemId}" aria-label="Preskoči: ${esc(item.requestedName)}">Preskoči</button>`}</div>`;
  };
  // Kupljeno i preskočeno idu na dno, u sklopljene odeljke.
  const grouped = (key, items) => {
    const by = g => items.filter(i => purchaseGroup(purchase, i) === g);
    const folded = (g, label) => {
      const rows = by(g);
      if (!rows.length) return '';
      const id = `${key}-${g}`;
      return `<details class="purchase-group" data-group="${id}" ${purchaseOpen[id] ? 'open' : ''}>
        <summary>${label} (${rows.length})</summary>${rows.map(row).join('')}</details>`;
    };
    return by('active').map(row).join('') + folded('bought', 'Kupljeno') + folded('skipped', 'Preskočeno');
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
          ${grouped(store.storeId, items)}
        </div>`;
      }).join('') +
      (unresolved.length ? `<div class="section">${unresolved.every(i => onTheWay(s, i)) ? 'Kupi usput' : 'Bez prodavnice'} ${pill(unresolved.length)}</div><div class="card flush">${grouped('none', unresolved)}</div>` : '') +
      `<button class="btn" data-act="finish-purchase">Završi kupovinu</button>
       <p class="muted small">Napredak kupovine čuva ovaj pregledač. Preskočeno ostaje na spisku za sledeći put; kupljeno možeš da skloniš sa spiska kad završiš.</p>`
  });
}

const purchaseName = (purchase, itemId) =>
  purchase.scenario.items.find(i => String(i.itemId) === String(itemId))?.requestedName || '';
const purchaseState = (purchase, itemId) =>
  ({ checked: Boolean(purchase.checked[itemId]), skipped: Boolean(purchase.skipped?.[itemId]) });

/** Preskočeno nestaje iz kupovine, a na spisku ostaje za sledeći put. */
function movePurchaseItem(itemId, to) {
  const purchase = saved.get('purchase');
  if (!purchase) return;
  const before = purchaseState(purchase, itemId);
  purchase.skipped = purchase.skipped || {};
  if (to === 'skipped') { purchase.skipped[itemId] = true; delete purchase.checked[itemId]; }
  else delete purchase.skipped[itemId];
  saved.set('purchase', purchase);
  showPurchase();
  if (to === 'skipped') {
    toast(`Preskočeno: ${purchaseName(purchase, itemId)}. Ostaje na spisku za sledeći put.`, 'Poništi', () => restorePurchaseItem(itemId, before));
  }
}

function restorePurchaseItem(itemId, state) {
  const purchase = saved.get('purchase');
  if (!purchase) return;
  purchase.skipped = purchase.skipped || {};
  if (state.checked) purchase.checked[itemId] = true; else delete purchase.checked[itemId];
  if (state.skipped) purchase.skipped[itemId] = true; else delete purchase.skipped[itemId];
  saved.set('purchase', purchase);
  if (location.hash === '#/kupovina') showPurchase();
}

function redrawPurchase(message, before, itemId) {
  clearTimeout(purchaseRedraw);
  purchaseRedraw = setTimeout(() => { if (location.hash === '#/kupovina') showPurchase(); }, 450);
  if (message) toast(message, 'Poništi', () => restorePurchaseItem(itemId, before));
}

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

export { markPurchase, movePurchaseItem, purchaseBought, purchaseItems, purchaseName, purchaseOpen, purchaseState, redrawPurchase, showPurchase, startPurchase };
