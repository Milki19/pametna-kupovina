// Pozivi serveru (/api/v1): sesija, ponovni pokušaj, keš provere i plana po
// verziji spiska, učitavanje spiska.

import { accessToken } from './session.js';
import { saved } from './storage.js';
import { pkTiming } from '../lib/timing.js';
import { flushDelete } from '../screens/list.js';

// Svaka izmena spiska podiže verziju. Provera proizvoda i preporuke se
// pamte za verziju za koju su izračunate, a najduže deset minuta, jer spisak
// može da se menja i u aplikaciji.
let listVersion = 0;
const REMEMBERED_FOR = 10 * 60 * 1000;
const remembered = (entry, key) => entry && entry.key === key && Date.now() - entry.at < REMEMBERED_FOR;

async function api(method, path, body, retried = false, signal = undefined) {
  if (method !== 'GET' && path.startsWith('shopping-lists')) listVersion++;
  let response;
  let token;
  const started = performance.now();
  try {
    token = await accessToken();
    response = await fetch('/api/v1/' + path, {
      method,
      headers: {
        'Authorization': 'Bearer ' + token,
        // FormData (fajl računa) sam postavlja svoj Content-Type.
        ...(body === undefined || body instanceof FormData ? {} : { 'Content-Type': 'application/json' })
      },
      body: body === undefined || body instanceof FormData ? body : JSON.stringify(body),
      signal
    });
  } catch (error) {
    // Otkazana pretraga (stiglo je novo slovo) nije greška veze.
    if (error.name === 'AbortError') throw error;
    throw new Error('Nema veze sa serverom. Proveri internet i probaj ponovo.');
  }
  if (response.status === 401 && !retried
      && (response.headers.get('WWW-Authenticate') || '').startsWith('Bearer')) {
    const session = saved.get('session');
    if (session?.access === token) saved.set('session', { ...session, expiresAt: 0 });
    return api(method, path, body, true, signal);
  }
  if (!response.ok) {
    let message = null;
    let field = null;
    try { ({ message, field } = await response.json()); } catch { /* nije JSON */ }
    const error = new Error(message || (response.status === 429
      ? 'Previše zahteva odjednom. Sačekaj minut pa probaj ponovo.'
      : 'Server trenutno ne odgovara. Probaj ponovo.'));
    error.status = response.status;
    if (field) error.field = field;
    throw error;
  }
  const text = await response.text();
  if (typeof pkTiming !== 'undefined') pkTiming.request(method, path, performance.now() - started);
  return text ? JSON.parse(text) : null;
}

/**
 * Jedan spisak po uređaju, kao u aplikaciji: zapamćen, pa postojeći na
 * serveru, pa tek onda nov. Dva
 * istovremena poziva (prvo otvaranje pa osvežen izgled tržišta) dele isti
 * odgovor, inače bi prazan pregledač napravio dva spiska i stavke bi završile
 * u onom koji se posle više ne otvara.
 */
let listLoading = null;
function loadList() {
  if (!listLoading) listLoading = loadListOnce().finally(() => { listLoading = null; });
  return listLoading;
}

/**
 * Spisak koji je ovaj pregledač imao više ne postoji (nalog obrisan ili
 * sesija ugašena). Kupovina po njemu ide sa njim, a kupac to saznaje na
 * spisku, umesto da zatekne prazan spisak bez reči.
 */
function forgetLostList() {
  saved.set('listId', null);
  saved.set('purchase', null);
  saved.set('listLost', true);
}

/**
 * Spisak se serveru predstavlja nasumičnim ID-jem (publicId), a ne rednim
 * brojem, koji otkriva koliko spiskova ima i ne krije se u linku. Broj i
 * dalje radi (Android 2.0.6), pa server bez publicId ostaje na broju.
 */
const listRef = list => list.publicId || list.id;

/** Pregledač koji je pamtio broj prelazi na nasumičan ID; kupovina u toku ide za njim. */
function rememberListRef(known, list) {
  const ref = listRef(list);
  if (known === ref) return;
  saved.set('listId', ref);
  const purchase = saved.get('purchase');
  if (purchase && purchase.listId === list.id) saved.set('purchase', { ...purchase, listId: ref });
}

async function loadListOnce() {
  await flushDelete();
  const id = saved.get('listId');
  if (id) {
    try {
      const list = await api('GET', `shopping-lists/${id}`);
      rememberListRef(id, list);
      return list;
    } catch (error) {
      if (error.status !== 404) throw error;
      forgetLostList();
    }
  }
  // Pregledač ne zna svoj spisak (obrisani podaci, nov pregledač sa istim
  // ključem), ali uređaj ga možda već ima: prvo se pita server, pa se tek
  // onda pravi nov, da ne bi stari spisak ostao sakriven pored novog praznog.
  const existing = await existingList();
  const ref = existing
      ? listRef(existing)
      : listRef(await api('POST', 'shopping-lists', { name: 'Moja kupovina' }));
  saved.set('listId', ref);
  return api('GET', `shopping-lists/${ref}`);
}

/**
 * Spisak koji uređaj već ima: onaj sa stavkama, a među njima poslednji
 * menjan (server ih daje od poslednje izmene). Samo prazni: poslednji.
 */
async function existingList() {
  const lists = await api('GET', 'shopping-lists');
  if (!Array.isArray(lists) || !lists.length) return null;
  return lists.find(list => list.itemCount > 0) || lists[0];
}

export { api, listRef, listVersion, loadList, remembered };
