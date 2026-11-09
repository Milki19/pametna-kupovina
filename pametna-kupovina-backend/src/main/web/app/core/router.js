// Rute (#/spisak, #/cene...), radnje dugmadi (data-act) i obrasci (data-form).
// Ekrani se ovde samo povezuju; svaki je u svom modulu u screens/.

import { api } from './api.js';
import { counted } from './format.js';
import { clearFormError, enterNext, failed, fieldError, formError, toast, view } from './screen.js';
import { saved } from './storage.js';
import { clearList, deleteItem, flushDelete, listState, mergeAllDuplicates, mergeDuplicates, removeBought, renderList, saveItem, showList } from '../screens/list.js';
import { addText, submitPaste } from '../screens/paste.js';

// Spisak (i Nalepi) je prvi ekran, pa stiže sa app.js. Ostali ekrani su
// posebni delovi (esbuild splitting) i učitavaju se tek kad zatrebaju, ili
// kad pregledač nema šta drugo da radi, da prelaz bude odmah. sw.js ih čuva
// za rad bez mreže. Ime u import() mora biti doslovno, da ga esbuild vidi.
const loaders = {
  account: () => import('../screens/account.js'),
  cards: () => import('../screens/cards.js'),
  matching: () => import('../screens/matching.js'),
  more: () => import('../screens/more.js'),
  origin: () => import('../screens/origin.js'),
  plan: () => import('../screens/plan.js'),
  prices: () => import('../screens/prices.js'),
  purchase: () => import('../screens/purchase.js'),
  receipt: () => import('../screens/receipt.js'),
  sales: () => import('../screens/sales.js'),
  barcode: () => import('../lib/barcode.js')
};
/** Učitani moduli, da ih događaji koriste bez čekanja. */
const loaded = {};
const loading = {};

/**
 * Modul ekrana. Kad deo ne stigne (nova verzija je obrisala stari deo sa
 * servera), stranica se jednom osveži; bez mreže i bez sačuvane kopije
 * greška kaže zašto.
 */
function screenModule(name) {
  if (loaded[name]) return Promise.resolve(loaded[name]);
  if (!loading[name]) {
    loading[name] = loaders[name]().then(module => {
      loaded[name] = module;
      // Nalog donosi svoje rute, dugmad i obrasce (domaćinstvo, potrošnja, prijava).
      if (name === 'account') {
        Object.assign(actions, module.accountActions);
        Object.assign(forms, module.accountForms);
      }
      return module;
    }, error => {
      delete loading[name];
      let reloaded = false;
      try { reloaded = sessionStorage.getItem('pk.reloadedFor') === name; } catch { /* privatni prozor */ }
      if (navigator.onLine && !reloaded) {
        try { sessionStorage.setItem('pk.reloadedFor', name); } catch { /* privatni prozor */ }
        location.reload();
      }
      const failure = new Error(navigator.onLine
        ? 'Ovaj ekran nije stigao sa servera. Osveži stranicu.'
        : 'Ovaj ekran još nije sačuvan na telefonu, a veza ne radi. Otvori ga kad se veza vrati.');
      failure.cause = error;
      throw failure;
    });
  }
  return loading[name];
}

/** Ruta iz modula koji se tek učitava; greška učitavanja ide na ekran sa „Pokušaj ponovo“. */
const lazyRoute = (name, show) => (...args) => screenModule(name)
  .then(module => module[show](...args), error => failed('Pametna kupovina', error, '#/' + currentRoute));
/** Ekran koji ne prima delove adrese. */
const bare = show => () => show();
/** Radnja dugmeta iz modula ekrana koji je to dugme i nacrtao. */
const lazy = (name, work) => async (...args) => work(await screenModule(name), ...args);

/** Kad pregledač miruje: ostali ekrani se učitaju unapred (i sw.js ih sačuva). */
function preloadScreens() {
  const idle = window.requestIdleCallback || (work => setTimeout(work, 1500));
  const names = Object.keys(loaders);
  const next = () => {
    const name = names.shift();
    if (name) screenModule(name).catch(() => {}).finally(() => idle(next));
  };
  idle(next);
}

const routes = {
  spisak: showList,
  provera: lazyRoute('matching', 'showMatching'),
  lokacija: bare(lazyRoute('origin', 'showLocation')),
  preporuke: lazyRoute('plan', 'showRecommendation'),
  kupovina: lazyRoute('purchase', 'showPurchase'),
  cene: bare(lazyRoute('prices', 'renderSearch')),
  akcije: lazyRoute('sales', 'showSales'),
  proizvod: lazyRoute('prices', 'showProduct'),
  kartice: lazyRoute('cards', 'showCards'),
  vise: lazyRoute('more', 'showMore'),
  vreme: lazyRoute('more', 'showTripCosts'),
  racun: lazyRoute('receipt', 'showReceipt'),
  // Rute naloga (accountRoutes u account.js).
  domacinstvo: lazyRoute('account', 'showHousehold'),
  potrosnja: lazyRoute('account', 'showSpending')
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
  // Ekran koji još nije učitan nema ni zapamćenu listu.
  const search = loaded.prices?.search;
  const sales = loaded.sales?.sales;
  if ((name === 'cene' && search?.page) || (name === 'akcije' && sales?.page && sales.place === loaded.sales.salesPlace())) {
    Promise.resolve(shown).then(() => window.scrollTo(0, scrolls[name] || 0));
  }
  if (!preloaded) {
    preloaded = true;
    Promise.resolve(shown).finally(preloadScreens);
  }
}
let preloaded = false;

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
  'merge-all': mergeAllDuplicates,
  calculate: () => { location.hash = '#/provera'; },
  choose: lazy('matching', ({ matching, resolve }, el) => {
    const item = matching.items.find(i => i.itemId === Number(el.dataset.item));
    return resolve(item.itemId, item.candidates[Number(el.dataset.candidate)]);
  }),
  reject: lazy('matching', ({ resolve }, el) => resolve(Number(el.dataset.item), null)),
  flexible: lazy('matching', ({ useAsFlexible }, el) => useAsFlexible(Number(el.dataset.item))),
  'to-location': () => { location.hash = '#/lokacija'; },
  locate: lazy('origin', ({ locate }) => locate()),
  travel: lazy('origin', ({ showLocation }, el) => { saved.set('walking', el.dataset.value === 'WALKING'); saved.set('nearOnly', null); showLocation(); }),
  'near-only': lazy('plan', ({ showRecommendation }, el) => { saved.set('nearOnly', el.dataset.value === 'on' ? true : null); showRecommendation(); }),
  'use-last': lazy('origin', ({ useOrigin }) => { const o = saved.get('origin'); useOrigin(o.lat, o.lng, o.label); }),
  'use-place': lazy('origin', ({ places, useOrigin }, el) => { const p = places.results[Number(el.dataset.index)]; useOrigin(p.latitude, p.longitude, p.name); }),
  scenario: lazy('plan', ({ chooseScenario }, el) => chooseScenario(el.dataset.type)),
  'start-purchase': lazy('purchase', ({ startPurchase }) => startPurchase()),
  'wrong-product': lazy('matching', ({ wrongProduct }, el) => wrongProduct(Number(el.dataset.item))),
  'item-menu': el => {
    const menu = document.getElementById(el.getAttribute('aria-controls'));
    menu.hidden = !menu.hidden;
    el.setAttribute('aria-expanded', String(!menu.hidden));
  },
  'unlocated-more': lazy('plan', ({ toggleUnlocated }) => toggleUnlocated()),
  'pick-open': lazy('matching', ({ openPick }, el) => openPick(Number(el.dataset.item))),
  'pick-cancel': lazy('matching', ({ cancelPick }) => cancelPick()),
  pick: lazy('matching', ({ picking, pickProduct }, el) => pickProduct(picking.results[Number(el.dataset.index)])),
  'pick-better': lazy('matching', ({ picking, pickProduct }) => pickProduct(picking.better.product, true)),
  'pick-keep': lazy('matching', ({ picking, pickProduct }) => pickProduct(picking.better.chosen, true)),
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
  'skip-item': lazy('purchase', ({ movePurchaseItem }, el) => movePurchaseItem(el.dataset.item, 'skipped')),
  'unskip-item': lazy('purchase', ({ movePurchaseItem }, el) => movePurchaseItem(el.dataset.item, 'active')),
  'remove-bought': removeBought,
  'clear-list': () => {
    const count = listState.list.items.length;
    if (!confirm(`Obrisati ceo spisak (${counted(count, 'stavka', 'stavke', 'stavki')})? Ovo ne može da se vrati.`)) return;
    return clearList();
  },
  'start-new-list': clearList,
  'keep-list': () => { saved.set('purchaseDone', null); renderList(); },
  more: lazy('prices', ({ runSearch }) => runSearch(true)),
  'add-product': lazy('prices', ({ addProduct, search }, el) => addProduct(search.items[Number(el.dataset.index)])),
  'add-current': lazy('prices', ({ addProduct }) => addProduct({ name: window.currentProduct.name, canonicalProductId: window.currentProduct.canonicalProductId })),
  'add-card': lazy('cards', ({ cards, renderCards }) => { cards.adding = true; renderCards(); }),
  'cancel-card': lazy('cards', ({ cards, renderCards }) => { cards.adding = false; renderCards(); }),
  'show-card': lazy('cards', ({ cards, renderCards }, el) => { cards.shown = Number(el.dataset.id); renderCards(); }),
  'close-card': lazy('cards', ({ cards, renderCards }) => { cards.shown = null; renderCards(); }),
  'delete-card': lazy('cards', async ({ cards, showCards }, el) => {
    if (!confirm('Obrisati ovu karticu?')) return;
    await api('DELETE', `loyalty-cards/${el.dataset.id}`);
    cards.shown = null;
    await showCards();
  }),
  'delete-account': lazy('account', ({ deleteAccount }) => deleteAccount()),
  'trip-reset': lazy('more', ({ saveTripCosts }) => saveTripCosts(null, null, null)),
  theme: el => {
    const value = el.dataset.value || null;
    saved.set('theme', value);
    window.applyTheme(value);
    document.querySelectorAll('[data-act="theme"]').forEach(b => b.setAttribute('aria-pressed', String(b === el)));
  },
  // Ovde se pri učitavanju naloga dodaju accountActions (screenModule).
  'sale-sort': lazy('sales', ({ sales, loadSales }, el) => { sales.sort = el.dataset.value; return loadSales(); }),
  'sale-radius': lazy('sales', ({ sales, loadSales }, el) => {
    sales.radius = Number(el.dataset.value);
    saved.set('saleRadius', sales.radius);
    if (!sales.radius && sales.sort === 'NEAREST') sales.sort = 'DISCOUNT';
    return loadSales();
  }),
  'sale-retailer': lazy('sales', ({ sales, loadSales }, el) => {
    sales.retailer = el.dataset.value || null;
    sales.retailerName = sales.retailers.find(r => r.code === sales.retailer)?.name || null;
    return loadSales();
  }),
  'sale-category': lazy('sales', ({ sales, loadSales }, el) => { sales.category = el.dataset.value || null; return loadSales(); }),
  'sales-more': lazy('sales', ({ loadSales }) => loadSales(true)),
  'sales-locate': lazy('sales', ({ salesLocate }) => salesLocate()),
  'add-sale': lazy('sales', async ({ sales, renderSales }, el) => {
    const [{ addProduct }, { chainWithoutAddresses }] = await Promise.all([screenModule('prices'), screenModule('matching')]);
    const p = sales.items[Number(el.dataset.index)];
    await addProduct({ name: p.name, productFamilyId: p.productFamilyId, canonicalProductId: p.canonicalProductId });
    renderSales();
    if (await chainWithoutAddresses(p.retailerCode)) {
      toast(`Na spisku: ${p.name}. ${p.retailerName} ne objavljuje adrese prodavnica, pa ova cena neće ući u plan.`);
    }
  })
};

const forms = {
  // Ovde se pri učitavanju naloga dodaju accountForms (screenModule).
  'pick-search': lazy('matching', ({ runPick }, data) => runPick(String(data.get('q') || ''))),
  'trip-costs': lazy('more', ({ saveTripCosts, tripAmount, tripCosts }, data) => (view.querySelector('[data-trip-saved]')?.remove(), saveTripCosts(
    tripAmount(data, 'hour', tripCosts.maxValuePerHour),
    tripAmount(data, 'stop', tripCosts.maxCostPerStop),
    tripAmount(data, 'km', tripCosts.maxCostPerKm)))),
  'add-many': data => submitPaste(String(data.get('text') || '')),
  'edit-item': (data, form) => {
    // Izmenjena stavka više nije „neprepoznata iz nalepljenog spiska“.
    listState.pastedUnknown.delete(Number(form.dataset.id));
    return saveItem(Number(form.dataset.id), data);
  },
  place: lazy('origin', async ({ places, showLocation }, data) => {
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
  }),
  search: lazy('prices', ({ search, runSearch }, data) => { search.query = String(data.get('q') || ''); document.activeElement.blur(); return runSearch(); }),
  card: lazy('cards', async ({ cards, showCards }, data) => {
    const { ean13Valid } = await screenModule('barcode');
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
  })
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
  if (event.target.matches('[data-sale-filter]') && loaded.prices) {
    loaded.prices.search.onSale = event.target.checked;
    loaded.prices.runSearch();
    return;
  }
  const box = event.target.closest('[data-check]');
  if (!box || !loaded.purchase) return;
  const { markPurchase, purchaseName, purchaseState, redrawPurchase } = loaded.purchase;
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
  if (group && loaded.purchase) loaded.purchase.purchaseOpen[group.dataset.group] = group.open;
}, true);

document.addEventListener('submit', event => {
  const handler = forms[event.target.dataset.form];
  if (!handler) return;
  event.preventDefault();
  run(event.target.querySelector('button:not([type=button])'), () => handler(new FormData(event.target), event.target), event.target);
});

export { currentRoute, productFrom, route, routes, run, salesFrom, screenModule, tripFrom };
