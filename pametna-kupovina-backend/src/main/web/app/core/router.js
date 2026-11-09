// Rute (#/spisak, #/cene...), radnje dugmadi (data-act) i obrasci (data-form).
// Ekrani se ovde samo povezuju; svaki je u svom modulu u screens/.

import { api } from './api.js';
import { counted } from './format.js';
import { clearFormError, enterNext, fieldError, formError, toast, view } from './screen.js';
import { saved } from './storage.js';
import { ean13Valid } from '../lib/barcode.js';
import { accountActions, accountForms, accountRoutes, deleteAccount } from '../screens/account.js';
import { cards, renderCards, showCards } from '../screens/cards.js';
import { clearList, deleteItem, flushDelete, listState, mergeDuplicates, removeBought, renderList, saveItem, showList } from '../screens/list.js';
import { cancelPick, chainWithoutAddresses, matching, openPick, picking, pickProduct, resolve, runPick, showMatching, useAsFlexible, wrongProduct } from '../screens/matching.js';
import { saveTripCosts, showMore, showTripCosts, tripAmount, tripCosts } from '../screens/more.js';
import { locate, places, showLocation, useOrigin } from '../screens/origin.js';
import { addText, submitPaste } from '../screens/paste.js';
import { chooseScenario, showRecommendation, toggleUnlocated } from '../screens/plan.js';
import { addProduct, renderSearch, runSearch, search, showProduct } from '../screens/prices.js';
import { markPurchase, movePurchaseItem, purchaseName, purchaseOpen, purchaseState, redrawPurchase, showPurchase, startPurchase } from '../screens/purchase.js';
import { showReceipt } from '../screens/receipt.js';
import { loadSales, renderSales, sales, salesLocate, salesPlace, showSales } from '../screens/sales.js';

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

// Detalji se otvaraju iz pretrage i iz akcija; Nazad vodi tamo odakle se došlo.
let productFrom = '#/cene';
// Akcije se otvaraju sa spiska i iz cena; Nazad vodi tamo odakle se došlo.
let salesFrom = '#/cene';

let tripFrom = '#/vise';

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
    enterNext(before < 0 || before === after ? 'enter' : after > before ? 'from-r' : 'from-l');
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
  'add-many': () => { listState.adding = 'paste'; listState.editing = null; listState.rejected = null; listState.pasteAsk = null; renderList(); },
  'cancel-add': () => { listState.adding = null; listState.rejected = null; listState.pasteAsk = null; renderList(); },
  'paste-ask-cancel': () => { listState.pasteAsk = null; renderList(); },
  'paste-add': () => { const text = listState.pasteAsk; listState.pasteAsk = null; return addText(text); },
  'paste-replace': async () => {
    const text = listState.pasteAsk;
    listState.pasteAsk = null;
    await clearList();
    return addText(text);
  },
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
  scenario: el => chooseScenario(el.dataset.type),
  'start-purchase': startPurchase,
  'wrong-product': el => wrongProduct(Number(el.dataset.item)),
  'item-menu': el => {
    const menu = document.getElementById(el.getAttribute('aria-controls'));
    menu.hidden = !menu.hidden;
    el.setAttribute('aria-expanded', String(!menu.hidden));
  },
  'unlocated-more': toggleUnlocated,
  'pick-open': el => openPick(Number(el.dataset.item)),
  'pick-cancel': cancelPick,
  pick: el => pickProduct(picking.results[Number(el.dataset.index)]),
  'pick-better': () => pickProduct(picking.better.product, true),
  'pick-keep': () => pickProduct(picking.better.chosen, true),
  'lost-list-ok': () => { saved.set('listLost', null); renderList(); },
  'finish-purchase': () => {
    if (!confirm('Završi kupovinu? Posle toga biraš da li se kupljeno sklanja sa spiska.')) return;
    const purchase = saved.get('purchase');
    saved.set('purchase', null);
    saved.set('purchaseDone', {
      listId: purchase.listId,
      bought: purchase.scenario.items.filter(i => purchase.checked[i.itemId]).map(i => i.itemId)
    });
    location.hash = '#/spisak';
  },
  'skip-item': el => movePurchaseItem(el.dataset.item, 'skipped'),
  'unskip-item': el => movePurchaseItem(el.dataset.item, 'active'),
  'remove-bought': removeBought,
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
  'sale-radius': el => {
    sales.radius = Number(el.dataset.value);
    saved.set('saleRadius', sales.radius);
    if (!sales.radius && sales.sort === 'NEAREST') sales.sort = 'DISCOUNT';
    return loadSales();
  },
  'sale-retailer': el => {
    sales.retailer = el.dataset.value || null;
    sales.retailerName = sales.retailers.find(r => r.code === sales.retailer)?.name || null;
    return loadSales();
  },
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
  'add-many': data => submitPaste(String(data.get('text') || '')),
  'edit-item': (data, form) => {
    // Izmenjena stavka više nije „neprepoznata iz nalepljenog spiska“.
    listState.pastedUnknown.delete(Number(form.dataset.id));
    return saveItem(Number(form.dataset.id), data);
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
  const before = purchaseState(purchase, box.dataset.check);
  purchase.checked[box.dataset.check] = box.checked;
  if (box.checked && purchase.skipped) delete purchase.skipped[box.dataset.check];
  saved.set('purchase', purchase);
  markPurchase(box, purchase);
  // Kvačica i precrtavanje se odigraju, pa red pređe u svoj odeljak.
  redrawPurchase(box.checked ? `Kupljeno: ${purchaseName(purchase, box.dataset.check)}` : null, before, box.dataset.check);
});

document.addEventListener('toggle', event => {
  const group = event.target.closest?.('[data-group]');
  if (group) purchaseOpen[group.dataset.group] = group.open;
}, true);

document.addEventListener('submit', event => {
  const handler = forms[event.target.dataset.form];
  if (!handler) return;
  event.preventDefault();
  run(event.target.querySelector('button:not([type=button])'), () => handler(new FormData(event.target), event.target), event.target);
});

export { currentRoute, productFrom, route, routes, run, salesFrom, tripFrom };
