// Akcije: popusti u krugu oko kupca ili po lancu.

import { api, loadList } from '../core/api.js';
import { amountLabel, counted, distance, esc, money, notice, shortDate, spinner } from '../core/format.js';
import { icon, saleBadge, tier } from '../core/html.js';
import { salesFrom } from '../core/router.js';
import { screen, toast, view } from '../core/screen.js';
import { browserName } from '../core/session.js';
import { saved } from '../core/storage.js';
import { listState } from './list.js';
import { searchLocation } from './prices.js';

// Krug u metrima (0 = „Svi lanci"); pamti se izbor, podrazumevano 3 km.
const saleRadii = [1000, 3000, 10000, 0];
const savedSaleRadius = () => { const r = saved.get('saleRadius', 3000); return saleRadii.includes(r) ? r : 3000; };
let sales = { sort: 'DISCOUNT', category: null, retailer: null, retailerName: null, radius: savedSaleRadius(),
  place: null, page: null, items: [], categories: [], retailers: [], error: null };
const saleSorts = { DISCOUNT: 'Najveći popust', SAVING: 'Najveća ušteda', PRICE: 'Najniža cena', NEAREST: 'Najbliže' };
const radiusLabel = meters => meters ? distance(meters / 1000) : 'Svi lanci';

/** Sa sačuvanom polaznom tačkom samo radnje u izabranom krugu, osim kad kupac izabere „Svi lanci". */
const salesPlace = () => {
  const place = sales.radius ? searchLocation() : '';
  return place ? place + '&radiusMeters=' + sales.radius : '';
};

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
      sales.category ? '&category=' + encodeURIComponent(sales.category) : ''}${
      sales.retailer ? '&retailer=' + encodeURIComponent(sales.retailer) : ''}${sales.place}`);
    sales.page = page;
    sales.items = more ? sales.items.concat(page.items) : page.items;
    if (!more) { sales.categories = page.categories; sales.retailers = page.retailers || []; }
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
  const origin = saved.get('origin');
  const radius = page?.radiusMeters ? distance(page.radiusMeters / 1000) : null;
  // Izabrani lanac ostaje u redu i kad u novom krugu nema akcija, da može da se skine.
  const retailers = sales.retailer && !sales.retailers.some(r => r.code === sales.retailer)
    ? [{ code: sales.retailer, name: sales.retailerName || sales.retailer, productCount: 0 }, ...sales.retailers]
    : sales.retailers;
  screen({
    title: 'Akcije',
    subtitle: page ? (page.nearbyChecked
      ? (origin?.label ? `Blizu: ${origin.label} · do ${radius}` : `Radnje do ${radius} od tebe`)
      : 'Svi lanci') : null,
    back: salesFrom,
    html:
      (!saved.get('origin') ? `<button class="card soft row" style="text-align:left;cursor:pointer" data-act="sales-locate">
        <span class="letter" style="border-radius:50%;background:var(--mint);color:var(--on-mint)">${icon.pin}</span>
        <span class="grow"><span class="name" style="display:block">Samo radnje blizu mene</span>
        <span class="muted small">${browserName} će pitati za dozvolu</span></span>${icon.chevron}</button>`
        : `<div class="chips" role="group" aria-label="Koliko daleko">${saleRadii.map(r =>
          chip('sale-radius', String(r), radiusLabel(r), r === sales.radius)).join('')}</div>`) +
      `<div class="chips" role="group" aria-label="Redosled">${Object.entries(saleSorts)
        // „Najbliže" samo kad se zna odakle.
        .filter(([key]) => key !== 'NEAREST' || page?.nearbyChecked)
        .map(([key, label]) => chip('sale-sort', key, label, key === sales.sort)).join('')}</div>` +
      (retailers.length > 1 || sales.retailer ? `<div class="chips" role="group" aria-label="Lanac">${chip('sale-retailer', '', 'Svi', !sales.retailer)}${
        retailers.map(r => chip('sale-retailer', r.code, `${r.name} (${r.productCount})`, r.code === sales.retailer)).join('')}</div>` : '') +
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
    position => {
      saved.set('origin', { lat: position.coords.latitude, lng: position.coords.longitude });
      if (!sales.radius) sales.radius = 3000;
      loadSales();
    },
    error => toast(error.code === 1 ? `${browserName} nema dozvolu za lokaciju.` : 'Lokacija trenutno nije dostupna.', null, null, true),
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 300000 }
  );
}

export { loadSales, onListKeys, renderSales, sales, salesLocate, salesPlace, showSales };
