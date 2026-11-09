// Spisak: stavke, dodavanje, izmena, duplikati i brisanje sa „Vrati“.

import { api, isSameList, listRef, loadList } from '../core/api.js';
import { amountLabel, counted, decimal, esc, notice, pill, separators, spinner } from '../core/format.js';
import { icon, salesShortcut } from '../core/html.js';
import { failed, fieldError, screen, toast, view } from '../core/screen.js';
import { reportProblem } from '../core/session.js';
import { saved } from '../core/storage.js';
import { account } from './account.js';
import { addLabel, paste, pasteAskCard, pastedUnknown, pastePreviewHtml, rejectedMark, rejectedNote } from './paste.js';
import { purchaseBought, purchaseItems } from './purchase.js';

function itemAmount(item) {
  const c = item.flexibleConstraints;
  if (!(c && c.targetQuantity)) return `${decimal(item.quantity)} kom`;
  const amount = amountLabel(c.targetQuantity * item.quantity, c.requiredBaseUnit);
  const weight = piecesWeight(item);
  return weight ? `${amount} (${weight})` : amount;
}

// Na spisku napomena ide u drugi red, da naziv ne ostane u uskoj koloni.
function itemAmountHtml(item) {
  const weight = piecesWeight(item);
  if (!weight) return esc(itemAmount(item));
  const c = item.flexibleConstraints;
  return `${esc(amountLabel(c.targetQuantity * item.quantity, c.requiredBaseUnit))} <span class="approx">(${esc(weight)})</span>`;
}

// „limun 3“ je 3 komada, a ne 3 kg: uz komade voća i povrća stoji i koliko
// otprilike teže, kao napomena u planu „3 kom ≈ 0,3 kg“ (šesti test, V156).
function piecesWeight(item) {
  const c = item.flexibleConstraints;
  if (!c || c.requiredBaseUnit !== 'piece' || !c.targetQuantity || !(item.pieceGrams > 0)) return null;
  const kilograms = Math.max(0.01, Math.round(c.targetQuantity * item.quantity * item.pieceGrams / 10) / 100);
  return `≈${decimal(kilograms, 2)} kg`;
}

// „mlevenog mesa“ i „mleveno meso“, „mleka 2,8%“ i „mleko 2 8“: iste reči u
// drugom padežu ili bez znakova nisu nova kategorija (peti test, 1.11.2026).
function sameKindWords(first, second) {
  const words = text => String(text || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
    .replace(/đ/gi, 'dj').toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim().split(' ').filter(Boolean);
  const a = words(first);
  const b = words(second);
  if (a.length !== b.length) return false;
  return a.every((word, i) => {
    const other = b[i];
    if (word === other) return true;
    if (/[0-9]/.test(word) || /[0-9]/.test(other)) return false;
    const keep = Math.max(3, Math.min(word.length, other.length) - 2);
    return word.length >= 3 && other.length >= 3 && word.slice(0, keep) === other.slice(0, keep);
  });
}

function itemRule(item) {
  if (item.matchingRule === 'EXACT_PRODUCT') return item.matchedCanonicalProductId || item.barcode ? 'Tačan barkod' : null;
  if (item.matchingRule === 'PRODUCT_FAMILY') return 'Isti proizvod, sve varijante';
  const c = item.flexibleConstraints || {};
  const parts = [];
  if (c.category && !sameKindWords(c.category, item.name)) parts.push('kategorija ' + c.category);
  if (c.requiredBrand) parts.push('brend ' + c.requiredBrand);
  const text = parts.join(', ');
  return text ? text[0].toUpperCase() + text.slice(1) : null;
}

function itemAttention(item) {
  if (item.matchingStatus === 'NEEDS_CONFIRMATION') return pill('Treba tvoja potvrda', 'warn');
  if (item.matchingStatus === 'UNMATCHED') return pill('Nije pronađeno', 'err');
  return '';
}

let listState = { list: null, adding: null, editing: null, rejected: null, pasteAsk: null, pastedUnknown: new Set() };

// Iste granice kao na serveru: količina 0,01–999, naziv koji se kuca do 80 znakova.
const MIN_QUANTITY = 0.01;
const MAX_QUANTITY = 999;
const MAX_NAME = 80;
const quantityProblem = 'Količina mora biti od 0,01 do 999.';

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
  // Bez upisane količine („mleko, 1 kom“) nudi se komad, ne kilogram.
  const unit = storedUnit(c.requiredBaseUnit, c.targetQuantity) || 'kom';
  const amount = c.targetQuantity ? inputNumber(c.targetQuantity / editUnits[unit][1]) : '';
  return `<form class="card" data-form="edit-item" data-id="${item.id}">
    <div class="label accent">Izmeni stavku</div>
    ${flexible ? `
      <label class="label" for="edit-name">Šta kupuješ</label>
      <input id="edit-name" name="name" type="text" value="${esc(item.name)}" autocomplete="off" maxlength="${MAX_NAME}" required>
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
  const listId = listRef(listState.list);
  const item = listState.list.items.find(i => i.id === id);
  const quantity = parseNumber(data.get('quantity'));
  if (!(quantity >= MIN_QUANTITY && quantity <= MAX_QUANTITY)) throw fieldError('quantity', quantityProblem);
  const flexible = item.matchingRule === 'FLEXIBLE_CATEGORY';
  let name = item.name;
  let constraints = item.flexibleConstraints;
  if (flexible) {
    name = String(data.get('name') || '').trim();
    if (!name) throw fieldError('name', 'Upiši šta kupuješ.');
    if (name.length > MAX_NAME) throw fieldError('name', `Naziv može imati najviše ${MAX_NAME} znakova.`);
    const c = item.flexibleConstraints || {};
    const amountText = String(data.get('amount') || '').trim();
    const amount = parseNumber(amountText);
    if (amountText && !(amount > 0)) throw fieldError('amount', 'Količina nije ispravna. Primer: 1,5.');
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

/** Ista stavka dva puta: upozorenje, a kad se količine mogu sabrati i dugme koje ih spaja. */
const sameName = name => String(name || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
  .replace(/đ/gi, 'dj').toLowerCase().replace(/\s+/g, ' ').trim();
const duplicateGroups = items => {
  const seen = new Map();
  items.forEach(item => seen.set(sameName(item.name), [...(seen.get(sameName(item.name)) || []), item]));
  return [...seen.values()].filter(group => group.length > 1);
};

/**
 * Prva stavka ostaje (mesto, pravilo, proizvod) i dobija zbir količina.
 * Null kad zbir nema smisla: drugo pravilo, proizvod ili brend, ili
 * količina u različitim jedinicama („mleko 1 l“ i „mleko“ od 1 kom).
 */
function mergePlan(group) {
  const [keep] = group;
  const c = keep.flexibleConstraints || {};
  const same = (item, pick) => sameName(pick(item) ?? '') === sameName(pick(keep) ?? '');
  const fits = group.every(item => item.matchingRule === keep.matchingRule
    // Server upisuje svoj pogodak i u „bilo koji“, pa se proizvod poredi samo kad ga pravilo traži.
    && (keep.matchingRule !== 'EXACT_PRODUCT' || (item.matchedCanonicalProductId === keep.matchedCanonicalProductId
      && same(item, i => i.barcode)))
    && (keep.matchingRule !== 'PRODUCT_FAMILY' || item.matchedProductFamilyId === keep.matchedProductFamilyId)
    && same(item, i => i.flexibleConstraints?.requiredBrand)
    && same(item, i => i.flexibleConstraints?.category));
  if (!fits) return null;
  const targets = group.map(item => item.flexibleConstraints?.targetQuantity || null);
  if (targets.every(t => t == null)) {
    const quantity = Math.round(group.reduce((sum, item) => sum + item.quantity, 0) * 100) / 100;
    return quantity <= MAX_QUANTITY ? { keep, quantity, constraints: keep.flexibleConstraints } : null;
  }
  if (!targets.every(t => t != null)
      || !group.every(item => item.flexibleConstraints.requiredBaseUnit === c.requiredBaseUnit)) return null;
  // Isto kao itemAmount: ukupno je ciljna količina puta broj.
  const target = group.reduce((sum, item) => sum + item.flexibleConstraints.targetQuantity * item.quantity, 0);
  if (target > (c.requiredBaseUnit === 'piece' ? MAX_QUANTITY : MAX_QUANTITY * 1000)) return null;
  return { keep, quantity: 1, constraints: { ...c, targetQuantity: Math.round(target * 1000) / 1000 } };
}

function duplicateNotice(items) {
  const groups = duplicateGroups(items);
  if (!groups.length) return '';
  const mergeable = groups.filter(mergePlan);
  const text = groups.map(group => `„${group[0].name}“ je na spisku ${group.length === 2 ? 'dva puta' : group.length + ' puta'}.`).join(' ')
    + (mergeable.length === groups.length ? ' Spoji ih u jednu stavku ili obriši višak.'
      : mergeable.length ? ' Gde se količine mogu sabrati, spoji ih; ostalo obriši ako je greška.'
        : ' Razlikuju se po proizvodu, brendu ili jedinici, pa ih ne spajam. Ako je to greška, obriši višak.');
  if (!mergeable.length) return notice(text, 'warn');
  // Veliki spisak sa više duplikata: jedno dugme za sve, pre pojedinačnih (šesti test, stavka 15).
  const all = mergeable.length > 1
    ? `<button class="btn primary" data-act="merge-all">Spoji sve (${mergeable.length})</button>` : '';
  return `<div class="card">${notice(text, 'warn')}<div class="btn-row wrap">${all}${mergeable.map(group =>
    `<button class="btn tonal" data-act="merge" data-id="${group[0].id}">Spoji „${esc(group[0].name)}“</button>`).join('')}</div></div>`;
}

/** Prva stavka grupe dobija zbir, ostale se brišu. */
async function mergeGroup(listId, { keep, quantity, constraints }, group) {
  await api('PUT', `shopping-lists/${listId}/items/${keep.id}`, {
    ...itemRequest(keep), quantity, flexibleConstraints: constraints
  });
  for (const extra of group.slice(1)) {
    await api('DELETE', `shopping-lists/${listId}/items/${extra.id}`)
      .catch(error => { if (error.status !== 404) throw error; });
  }
}

/** Spaja jednu grupu; spisak se zatim čita sa servera. */
async function mergeDuplicates(id) {
  await flushDelete();
  const listId = listRef(listState.list);
  const group = duplicateGroups(listState.list.items).find(g => g[0].id === id);
  const plan = group && mergePlan(group);
  if (!plan) return renderList();
  await mergeGroup(listId, plan, group);
  listState.editing = null;
  listState.list = await api('GET', `shopping-lists/${listId}`);
  renderList();
  const merged = listState.list.items.find(i => i.id === plan.keep.id);
  toast(merged ? `Spojeno: ${merged.name}, ${itemAmount(merged)}.` : 'Spojeno.');
}

/**
 * „Spoji sve“: svaka grupa koja se može sabrati, istim zahtevima kao jedno
 * „Spoji“. Grupe koje se ne mogu sabrati ostaju sa upozorenjem. Kad jedna
 * izmena ne uspe, spisak se ipak čita sa servera, pa se vidi šta je spojeno.
 */
async function mergeAllDuplicates() {
  await flushDelete();
  const listId = listRef(listState.list);
  const groups = duplicateGroups(listState.list.items)
    .map(group => ({ group, plan: mergePlan(group) })).filter(g => g.plan);
  let done = 0;
  try {
    for (const { group, plan } of groups) {
      await mergeGroup(listId, plan, group);
      done++;
    }
  } finally {
    listState.editing = null;
    listState.list = await api('GET', `shopping-lists/${listId}`);
    renderList();
  }
  toast(`Spojeno: ${counted(done, 'stavka', 'stavke', 'stavki')}.`);
}

/** Stavka kakva jeste, u obliku koji server prima za dodavanje i izmenu. */
const itemRequest = item => ({
  name: item.name,
  rawInput: item.rawInput,
  barcode: item.barcode,
  canonicalProductId: item.matchingRule === 'EXACT_PRODUCT' ? item.matchedCanonicalProductId : null,
  productFamilyId: item.matchingRule === 'PRODUCT_FAMILY' ? item.matchedProductFamilyId : null,
  quantity: item.quantity,
  matchingRule: item.matchingRule,
  flexibleConstraints: item.flexibleConstraints
});

function lostListNotice() {
  if (!saved.get('listLost')) return '';
  // Spisak se vratio (ili je već popunjen), pa poruka o praznom više ne važi.
  if (listState.list.items.length) {
    saved.set('listLost', null);
    return '';
  }
  const signedIn = typeof account !== 'undefined' && account.state?.signedIn;
  return `<div class="card">${notice('Tvoj prethodni spisak više nije dostupan, pa je ovo nov, prazan spisak.'
      + (signedIn ? '' : ' Da se to ne ponovi, prijavi se preko Google-a: spisak se tada čuva uz nalog.'), 'warn')}
    <div class="btn-row">
      <button class="btn" data-act="lost-list-ok">U redu</button>
      ${signedIn ? '' : '<a class="btn primary" href="#/vise">Prijavi se</a>'}
    </div></div>`;
}

/**
 * Kupljeno iz završene kupovine što je još na ovom spisku. Stara kupovina
 * (pre 2.0.32) pamti samo „završeno", pa nema šta da se ponudi.
 */
function boughtOnList(items) {
  const done = saved.get('purchaseDone');
  if (!done || done === true || !isSameList(done.listId, listState.list)) return [];
  const bought = new Set(done.bought.map(String));
  return items.filter(i => bought.has(String(i.id)));
}

/** Posle završene kupovine: sa spiska se sklanja samo kupljeno; ceo spisak se briše sporednim dugmetom. */
function purchaseDoneNotice(items) {
  if (!saved.get('purchaseDone')) return '';
  if (!items.length) { saved.set('purchaseDone', null); return ''; }
  const bought = boughtOnList(items);
  if (!bought.length) {
    return `<div class="card">${notice('Kupovina je završena. Stavke su i dalje na spisku.', 'pos')}
      <div class="btn-row">
        <button class="btn" data-act="keep-list">Zadrži spisak</button>
        <button class="btn" data-act="start-new-list">Obriši ceo spisak</button>
      </div></div>`;
  }
  const left = items.filter(i => !bought.includes(i));
  const names = left.slice(0, 3).map(i => i.name).join(', ') + (left.length > 3 ? ', …' : '');
  return `<div class="card">${notice(`Kupovina je završena. Skloniti sa spiska kupljeno (${bought.length})?`, 'pos')}
    <p class="muted small">${left.length ? esc(`Na spisku ostaje ${left.length}: ${names}`) : 'Spisak posle toga ostaje prazan.'}</p>
    <div class="btn-row">
      <button class="btn" data-act="keep-list">Zadrži sve</button>
      <button class="btn primary" data-act="remove-bought">Skloni kupljeno</button>
    </div>
    <button class="btn danger" data-act="start-new-list">Obriši ceo spisak</button></div>`;
}

/** Briše samo kupljene stavke, jednu po jednu, istim zahtevom kao brisanje na spisku. */
async function removeBought() {
  await flushDelete();
  const listId = listRef(listState.list);
  const bought = boughtOnList(listState.list.items);
  for (const item of bought) {
    // Već obrisano (drugi telefon u domaćinstvu) nije greška.
    await api('DELETE', `shopping-lists/${listId}/items/${item.id}`).catch(error => { if (error.status !== 404) throw error; });
  }
  saved.set('purchaseDone', null);
  listState.list = await api('GET', `shopping-lists/${listId}`);
  renderList();
  toast(`Sklonjeno sa spiska: ${counted(bought.length, 'stavka', 'stavke', 'stavki')}`);
}

/** Sve stavke odjednom; spisak ostaje isti, pa ga ukućani i aplikacija i dalje vide. */
async function clearList() {
  // Brisanje jedne stavke koje čeka „Vrati“ ovde je suvišno: ide ceo spisak.
  // Čekanje na njega je jednom zadržalo i ovaj zahtev, pa se samo otkazuje.
  if (pendingDelete) {
    clearTimeout(pendingDelete.timer);
    pendingDelete = null;
  }
  const listId = listRef(listState.list);
  try {
    await api('DELETE', `shopping-lists/${listId}/items`);
  } catch (error) {
    reportProblem('clear-list', error);
    throw error;
  }
  saved.set('purchaseDone', null);
  const purchase = saved.get('purchase');
  if (isSameList(purchase?.listId, listState.list)) saved.set('purchase', null);
  listState.editing = null;
  listState.list = await api('GET', `shopping-lists/${listId}`);
  renderList();
}

function renderList() {
  const items = listState.list.items;
  let purchase = saved.get('purchase');
  // Kupovina po spisku koji ovaj pregledač više nema (stari, ili drugi u domaćinstvu).
  if (purchase?.listId != null && !isSameList(purchase.listId, listState.list)) {
    saved.set('purchase', null);
    purchase = null;
  }
  const pasteText = listState.pasteAsk ?? listState.rejected?.text ?? '';
  // Upravo nalepljeno, a neprepoznato, stoji na vrhu dok se ne proveri.
  const shown = [...items.filter(pastedUnknown), ...items.filter(item => !pastedUnknown(item))];
  // Jedno polje za jednu stavku i za ceo nalepljen spisak (šesti test, 8.11.2026).
  const lineCount = paste(pasteText).filter(line => !line.skipped).length;
  const form = listState.adding
    ? `<form class="card" data-form="add-many">
         <label class="label" for="many">Šta kupuješ</label>
         <textarea id="many" name="text" class="add-text" placeholder="mleko 2l&#10;hleb&#10;ćevapi 3kg&#10;2x pivo" aria-describedby="add-hint paste-preview" ${rejectedMark('many')}required>${esc(pasteText)}</textarea>${rejectedNote()}
         <p class="muted small" id="add-hint">Jedna stavka, ili ceo spisak iz poruke ili beleške: svaka stavka u svom redu.</p>
         <div id="paste-preview">${lineCount > 1 || paste(pasteText).some(line => line.skipped || line.note) ? pastePreviewHtml(pasteText) : ''}</div>
         <div class="btn-row"><button type="button" class="btn" data-act="cancel-add">Odustani</button><button class="btn primary" ${lineCount ? '' : 'disabled'}>${addLabel(lineCount)}</button></div>
       </form>` + pasteAskCard()
    : '';
  screen({
    title: 'Moj spisak',
    subtitle: items.length ? counted(items.length, 'stavka', 'stavke', 'stavki') : null,
    html: lostListNotice() + purchaseDoneNotice(items) +
      (purchase ? `<a class="card row" href="#/kupovina" style="text-decoration:none;color:inherit">
          <div class="grow"><div class="name">Kupovina u toku</div>
          <div class="muted small">Kupljeno ${purchaseBought(purchase)} od ${purchaseItems(purchase).length}</div></div>${icon.chevron}</a>` : '') +
      (listState.adding ? '' : `<button class="add-row" data-act="add-many">${icon.add}Dodaj ili nalepi spisak</button>`) + form +
      // Akcije su prečica dok je spisak prazan; posle su na kartici Cene, da stavke budu na prvom ekranu.
      (listState.adding || items.length ? '' : salesShortcut()) +
      (items.length
        ? `<div class="section">Stavke na spisku ${pill(items.length)}</div>` + duplicateNotice(items) + shown.map(item => item.id === listState.editing ? itemEditor(item) : `
          <div class="card item">
            <div class="row">
              <div class="item-tap" data-act="edit" data-id="${item.id}" role="button" tabindex="0" aria-label="Izmeni ${esc(item.name)}">
                <div class="grow">
                  ${itemAttention(item) || (pastedUnknown(item) ? pill('Nije prepoznato', 'warn') : '')}
                  <div class="name">${esc(item.name)}</div>
                  ${item.note ? `<div class="muted small">${esc(item.note)}</div>` : ''}
                  ${itemRule(item) ? `<div class="muted small">${esc(itemRule(item))}</div>` : ''}
                </div>
                <span class="amount">${itemAmountHtml(item)}</span>
              </div>
              <button class="icon-action" data-act="delete" data-id="${item.id}" aria-label="Obriši ${esc(item.name)}">${icon.trash}</button>
            </div>
          </div>`).join('') +
          `<button class="btn danger" data-act="clear-list">${icon.trash}Obriši ceo spisak</button>`
        : `<div class="card soft center"><div class="title">Spisak je prazan</div>
           <p class="muted">Dodaj stavku po stavku, ili nalepi ceo spisak iz poruke ili beleške.</p></div>`),
    action: `<button class="btn primary main" data-act="calculate" ${items.length ? '' : 'disabled'}>Izračunaj</button>`
  });
  const field = view.querySelector('[data-form] input:not([type=hidden]), [data-form] textarea');
  if (field && !listState.editing) field.focus();
  const editing = view.querySelector('[data-form=edit-item]');
  if (editing) editing.scrollIntoView({ block: 'nearest' });
}

// Brisanje se šalje serveru tek kad prođe vreme za „Vrati“, pa vraćena stavka
// zadržava svoje mesto i oznaku („Nije pronađeno“) umesto da postane nova.
let pendingDelete = null;
let deleting = Promise.resolve();

/** Šalje brisanje koje čeka; ekrani koji čitaju spisak sa servera prvo čekaju ovo. */
function flushDelete() {
  if (pendingDelete) {
    const { listId, item, timer } = pendingDelete;
    clearTimeout(timer);
    pendingDelete = null;
    deleting = api('DELETE', `shopping-lists/${listId}/items/${item.id}`)
      .catch(error => { if (error.status !== 404) toast(`Nije obrisano: ${item.name}. ${error.message}`, null, null, true); });
  }
  return deleting;
}
document.addEventListener('visibilitychange', () => { if (document.hidden) flushDelete(); });

async function deleteItem(id) {
  await flushDelete();
  const listId = listRef(listState.list);
  const index = listState.list.items.findIndex(i => i.id === id);
  const item = listState.list.items[index];
  listState.list.items = listState.list.items.filter(i => i.id !== id);
  renderList();
  pendingDelete = { listId, item, timer: setTimeout(flushDelete, 5000) };
  toast(`Obrisano: ${item.name}`, 'Vrati', async () => {
    if (pendingDelete?.item === item) {
      clearTimeout(pendingDelete.timer);
      pendingDelete = null;
      if (listState.list?.id === listId) {
        listState.list.items.splice(Math.min(index, listState.list.items.length), 0, item);
        renderList();
      }
      return;
    }
    await api('POST', `shopping-lists/${listId}/items`, itemRequest(item));
    listState.list = await api('GET', `shopping-lists/${listId}`);
    renderList();
  });
}

export { clearList, deleteItem, flushDelete, listState, MAX_QUANTITY, mergeAllDuplicates, mergeDuplicates, parseNumber, removeBought, renderList, saveItem, showList };
