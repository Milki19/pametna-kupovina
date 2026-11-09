// Izmene spiska bez mreže (u prodavnici bez signala): zahtev koji nije stigao
// do servera čeka ovde, u IndexedDB, i šalje se istim redom kad se veza vrati.
// Uz njega se čuva i poslednji prikazan spisak, da se otvori i bez mreže.
// Bez biblioteke; kad IndexedDB ne radi (privatni prozor), red je u memoriji
// i traje dok je stranica otvorena.

const DB = 'pk-outbox';
const QUEUE = 'queue';
const COPY = 'copy';

let opening = null;
function database() {
  if (!opening) {
    opening = new Promise((resolve, reject) => {
      if (!('indexedDB' in window)) { reject(new Error('no IndexedDB')); return; }
      const request = indexedDB.open(DB, 1);
      request.onupgradeneeded = () => {
        request.result.createObjectStore(QUEUE, { keyPath: 'id', autoIncrement: true });
        request.result.createObjectStore(COPY);
      };
      request.onsuccess = () => resolve(request.result);
      request.onerror = () => reject(request.error);
      request.onblocked = () => reject(new Error('IndexedDB blocked'));
    }).catch(() => null);
  }
  return opening;
}

/** Jedna transakcija; bez baze vraća null. */
async function stored(store, mode, work) {
  const db = await database();
  if (!db) return null;
  return new Promise((resolve, reject) => {
    const tx = db.transaction(store, mode);
    const request = work(tx.objectStore(store));
    tx.oncomplete = () => resolve(request ? request.result : null);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  }).catch(() => null);
}

// Ogledalo reda u memoriji: ekran broji izmene odmah, bez čekanja baze.
let entries = [];
let nextMemoryId = 1;
const ready = stored(QUEUE, 'readonly', store => store.getAll())
  .then(all => { if (Array.isArray(all)) entries = all.sort((a, b) => a.id - b.id); });

/** Koliko izmena čeka slanje. */
const waiting = () => entries.length;

/** Izmena koja čeka: { method, path, body, local }. local je kratko „šta je to“ za ekran. */
async function enqueue(entry) {
  await ready;
  const record = { ...entry, at: Date.now() };
  const id = await stored(QUEUE, 'readwrite', store => store.add(record));
  record.id = id ?? 'm' + nextMemoryId++;
  entries.push(record);
  return record;
}

async function drop(record) {
  entries = entries.filter(entry => entry !== record);
  if (typeof record.id === 'number') await stored(QUEUE, 'readwrite', store => store.delete(record.id));
}

let sending = null;
/**
 * Šalje izmene redom. Bez veze staje i ostavlja ostatak za sledeći put.
 * Stavka koja je u međuvremenu obrisana (404) nije greška. Izmena koju server
 * odbije (400, 403...) se ne šalje ponovo; vraća se u `refused`.
 * Vraća { sent, refused, left }.
 */
function send(api) {
  if (!sending) sending = sendAll(api).finally(() => { sending = null; });
  return sending;
}
async function sendAll(api) {
  await ready;
  let sent = 0;
  const refused = [];
  for (const record of [...entries]) {
    try {
      await api(record.method, record.path, record.body);
    } catch (error) {
      if (error.offline || !error.status || error.status >= 500 || error.status === 429) break;
      if (error.status !== 404) refused.push({ record, error });
    }
    await drop(record);
    sent++;
  }
  return { sent, refused, left: entries.length };
}

/** Poslednji prikazan spisak, sa izmenama koje još čekaju. */
const keepCopy = list => stored(COPY, 'readwrite', store => store.put(list, 'list'));
const copy = () => stored(COPY, 'readonly', store => store.get('list'));
/** Posle brisanja naloga: ništa ne čeka i ništa se ne pamti. */
async function forget() {
  entries = [];
  await stored(QUEUE, 'readwrite', store => store.clear());
  await stored(COPY, 'readwrite', store => store.clear());
}

export { copy, enqueue, forget, keepCopy, send, waiting };
