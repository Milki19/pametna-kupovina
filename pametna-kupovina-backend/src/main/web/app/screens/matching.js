// Korak 1: provera proizvoda, izbor iz pretrage i „Pogrešan proizvod“ iz plana.

import { api, listVersion, remembered } from '../core/api.js';
import { amountLabel, counted, decimal, esc, money, notice, pill, plural, spinner } from '../core/format.js';
import { failed, screen, toast, view } from '../core/screen.js';
import { saved } from '../core/storage.js';
import { flushDelete, MAX_QUANTITY } from './list.js';
import { distinctScenarios, recommendation, selectedType } from './plan.js';
import { pickTyping, searchable, searchLocation } from './prices.js';

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
function cancelPick() { pickTyping.cancel(); picking = null; renderMatching(); }

async function showMatching() {
  screen({ title: 'Provera proizvoda', back: '#/spisak', html: spinner('Tražim odgovarajuće proizvode…') });
  try {
    const listId = saved.get('listId');
    if (!listId) { location.hash = '#/spisak'; return; }
    await flushDelete();
    if (!(matching && remembered(matchingFor, `${listId}|${listVersion}`))) {
      matching = await api('POST', `shopping-lists/${listId}/matching?includeProductsWithoutBarcode=true`);
      // Dalje se spisak zove onako kako ga ovaj pregledač pamti (nasumičan ID).
      matching.listId = listId;
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

/** „Pogrešan proizvod" u planu: odluka se pamti kao odbijena, a zamena se bira u koraku 1. */
let pendingPick = null;
async function wrongProduct(itemId) {
  const planItem = recommendation && (distinctScenarios(recommendation).find(x => x.type === selectedType)
    || recommendation.recommendedBalance).items.find(i => i.itemId === itemId);
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

export { cancelPick, chainWithoutAddresses, matching, openPick, picking, pickProduct, renderPick, resolve, runPick, showMatching, useAsFlexible, wrongProduct };
