// Cene: pretraga proizvoda i detalji sa ponudama po radnjama.

import { api, listRef, loadList } from '../core/api.js';
import { amountLabel, counted, date, distance, esc, money, notice, pill, shortDate, spinner } from '../core/format.js';
import { discountPercent, icon, saleLine, salesShortcut, tier } from '../core/html.js';
import { productFrom } from '../core/router.js';
import { failed, screen, toast, view } from '../core/screen.js';
import { saved } from '../core/storage.js';
import { listState } from './list.js';
import { picking, renderPick, runPick } from './matching.js';
import { updatePastePreview } from './paste.js';

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
  } else if (field.id === 'many') {
    updatePastePreview(field);
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
  const added = await api('POST', `shopping-lists/${listRef(listState.list)}/items`, {
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

export { addProduct, pickTyping, renderSearch, runSearch, search, searchable, searchLocation, showProduct };
