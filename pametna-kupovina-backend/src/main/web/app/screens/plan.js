// Korak 3: preporuke (Jedna stanica, Najbolje ukupno, Najjeftinija korpa).

import { api } from '../core/api.js';
import { amountLabel, counted, date, decimal, distance, duration, esc, market, money, notice, number, perUnit, pill, plural, stalePrice, whole } from '../core/format.js';
import { icon } from '../core/html.js';
import { failed, screen } from '../core/screen.js';
import { saved } from '../core/storage.js';
import { flushDelete } from './list.js';
import { originLabel } from './origin.js';

let recommendation = null;
let recommendationFor = null;
// Cene se menjaju jednom dnevno, pri uvozu; sat vremena je dovoljno sveže.
const PLAN_REMEMBERED_FOR = 60 * 60 * 1000;
/** Ono od čega plan zavisi: stavke, količine i odluke, bez vremena izmene. */
const planInputs = list => JSON.stringify(list.items.map(i => [i.id, i.name, i.barcode, i.quantity, i.matchingRule,
  i.matchingStatus, i.matchedCanonicalProductId, i.matchedProductFamilyId, i.matchingDecisionId, i.flexibleConstraints]));
let selectedType = 'RECOMMENDED_BALANCE';
function chooseScenario(type) {
  selectedType = type;
  const s = recommendation && recommendation[scenarioKey[type]];
  if (s && s.detailsFailed) delete s.detailsFailed;
  renderRecommendation();
}
/** Plan je računat po staroj ceni vremena ili prevoza. */
function forgetRecommendation() { recommendation = null; recommendationFor = null; }

// Preporuke u dva koraka (šesti test: 100 stavki su bile 212 KB): server
// odmah šalje zbir i pokrivenost sva tri plana i stavke izabranog, a stavke
// drugog plana tek kad se on otvori.
const scenarioKey = { SINGLE_STORE: 'singleStore', RECOMMENDED_BALANCE: 'recommendedBalance', LOWEST_PRICE: 'lowestPrice' };
/** Broj stavki spiska; plan bez stavki ga nosi posebno. */
const itemTotal = s => s.itemCount ?? s.items.length;
/** Plan čije stavke još nisu stigle (stari server šalje sve, bez `detailed`). */
const withoutItems = s => s.detailed === false;
let recommendationUrl = null;
const loadingDetails = new Set();
async function loadDetails(result, type) {
  if (loadingDetails.has(type)) return;
  loadingDetails.add(type);
  try {
    const opened = await api('GET', `${recommendationUrl}&plan=${type}`);
    if (recommendation !== result) return;
    result[scenarioKey[type]] = opened[scenarioKey[type]];
  } catch (error) {
    if (recommendation !== result) return;
    result[scenarioKey[type]] = { ...result[scenarioKey[type]], detailsFailed: true };
  } finally {
    loadingDetails.delete(type);
  }
  if (recommendation === result && location.hash === '#/preporuke') renderRecommendation();
}
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
  const missing = itemTotal(s) - s.coveredItems - (s.onTheWay?.itemIds.length || 0);
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
function onTheWayNotice(s, walking = false) {
  const o = s.onTheWay;
  if (!o || !o.itemNames.length) return '';
  const names = o.itemNames.join(', ');
  // Peške se hod ne pretvara u dinare (utisak 31.10.).
  const cost = walking ? `oko ${duration(o.extraSeconds)} hoda više`
    : `+${whole(o.extraCost)} ${market.currency} i oko ${duration(o.extraSeconds)} više`;
  return notice(`${names.charAt(0).toUpperCase() + names.slice(1)} kupi usput. Zbog ${o.itemNames.length === 1 ? 'te stavke' : 'tih stavki'}
    bi plan imao još jednu prodavnicu: ${cost}.`);
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
  const inSingle = new Set(single.availableItemIds
    || single.items.filter(i => i.resultStatus === 'AVAILABLE').map(i => i.itemId));
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
    const url = `shopping-lists/${listId}/recommendations?latitude=${origin.lat}&longitude=${origin.lng}${travel}&unlocated=false`;
    recommendation = await api('GET', `${url}&plan=${selectedType}`);
    recommendationUrl = url;
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
  const waiting = withoutItems(s);
  if (waiting && !s.detailsFailed) loadDetails(result, s.type);
  const unresolved = s.items.filter(i => i.resultStatus !== 'AVAILABLE' && !onTheWay(s, i));
  const a = result.assumptions;
  const onFoot = a?.travelMode === 'WALKING';
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
          <span class="small muted">${onFoot ? 'korpa' : 'ukupno'}</span>
          ${x.available ? `<span class="small muted">${onFoot ? `~${duration(x.routeDurationSeconds)} hoda` : `korpa ${whole(x.basketCost)}`}</span>
            <span class="small"><b>${x.coveredItems} od ${itemTotal(x)}</b> ${plural(itemTotal(x), 'stavke', 'stavke', 'stavki')}</span>` : ''}
        </button>`).join('')}
      </div>
      <div class="card stripe">
        <div class="row" style="flex-wrap:wrap;gap:8px">${pill(scenarioBadge[s.type], 'pos')}
          ${s.available && s.savingsComparedWithSingleStore > 0 ? pill(`Ušteda ${whole(s.savingsComparedWithSingleStore)} ${market.currency}`, 'pos') : ''}</div>
        <div class="title">${scenarioTitle[s.type]}</div>
        ${s.available ? `
          <div class="big">${s.totalCost != null ? money(s.totalCost) : 'nema cene'}</div>
          ${s.totalCost != null ? `<p class="muted small">${onFoot ? `korpa · ~${duration(s.routeDurationSeconds)} hoda; vreme hoda je uračunato u izbor po tvojoj ceni sata`
            : `korpa ${money(s.basketCost)} + put i vreme ${money(s.totalCost - s.basketCost)}`}</p>` : ''}
          <div class="stats">
            <div><span class="small muted">Prodavnice</span><b>${s.stopCount}</b></div>
            <div><span class="small muted">Razdaljina</span><b>${distance(s.routeDistanceKm)}</b></div>
            <div><span class="small muted">Vreme</span><b>~${duration(s.routeDurationSeconds)}</b></div>
          </div>
          <div class="row"><span class="grow">Pokrivenost korpe</span><b style="color:var(--primary)">${s.coveredItems} od ${itemTotal(s)} ${plural(itemTotal(s), 'stavke', 'stavke', 'stavki')}</b></div>`
          : `<p>${esc(s.explanation)}</p>`}
      </div>` + (waiting ? (s.detailsFailed
        ? `<div class="card soft row"><span class="grow small">Stavke ovog plana nisu stigle.</span>
            <button class="btn small" data-act="scenario" data-type="${s.type}">Pokušaj ponovo</button></div>`
        : `<p class="center muted small" role="status">Učitavam stavke plana…</p><div class="skeleton" aria-hidden="true"></div>`) : '')
      + onTheWayNotice(s, onFoot) + nearOnlyOffer(s, result) + balanceExplanation(s, result) +
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
          s.available && onFoot ? `Korpa ${money(s.basketCost)}, oko ${duration(s.routeDurationSeconds)} hoda.` : null,
          s.available && !onFoot ? `Korpa ${money(s.basketCost)}, put ${money(s.travelCost)}, vreme ${money(s.timeCost)}, stajanja ${money(s.stopCost)}.` : null,
          onFoot ? `Peške kilometar ne košta. Vreme hoda računamo ${money(a.valuePerHour)} po satu i ${money(a.costPerStop)} po stajanju, pa plan biramo po korpi i tom vremenu. Prikazujemo samo korpu i minute hoda.`
            : `Put računamo ${money(a.costPerKm)} po kilometru, vreme ${money(a.valuePerHour)} po satu i ${money(a.costPerStop)} po stajanju.`,
          s.approximateRoute ? 'Udaljenosti su procena: vazdušna linija uvećana za ulice, bez saobraćaja.' : null,
          `Razmotreno ${counted(result.candidateStoreCount, 'prodavnica', 'prodavnice', 'prodavnica')} u krugu od ${decimal(a.candidateRadiusMeters / 1000, 1)} km.`
        ].filter(Boolean).map(line => `<p class="small">${esc(line)}</p>`).join('')}
        <a class="btn text small" href="#/vreme">Promeni cenu vremena i prevoza</a>
      </details>`,
    action: s.available && !waiting ? `<button class="btn primary main" data-act="start-purchase">Započni kupovinu po ovom planu</button>` : ''
  });
}

// Lanci bez adresa: jedna rečenica za sve, prva tri (najveća pokrivenost) i ostalo na zahtev.
let unlocatedOpen = false;
function toggleUnlocated() { unlocatedOpen = !unlocatedOpen; renderRecommendation(); }
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

export { chooseScenario, distinctScenarios, forgetRecommendation, mapsUrl, onTheWay, quantityLine, recommendation, scenarioTitle, selectedType, showRecommendation, storeAddress, toggleUnlocated };
