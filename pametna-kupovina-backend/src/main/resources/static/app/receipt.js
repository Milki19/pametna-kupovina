'use strict';

// ---------- Račun sa slike ili PDF-a ----------
// Digitalni račun iz aplikacije trgovine (Maxi, Lidl Plus...) stoji na istom
// telefonu, pa ga kamera ne može uhvatiti. Screenshot ili PDF ide serveru,
// koji pročita QR kod i zavede račun kao da je skeniran.

/** Caddy i server primaju do 1 MB; ostaje mesta za omot zahteva. */
const RECEIPT_MOST_BYTES = 950000;

const receiptState = { list: null, busy: false, result: null, error: null };

async function showReceipt() {
  receiptState.result = null;
  receiptState.error = null;
  renderReceipt();
  try {
    receiptState.list = await api('GET', 'receipts?limit=10');
  } catch {
    // Bez spiska ranijih računa ekran i dalje radi.
    receiptState.list = receiptState.list || [];
  }
  if (currentRoute === 'racun') renderReceipt();
}

/** Dan izdavanja u vremenskoj zoni tržišta, ne u UTC-u. */
function receiptDay(iso) {
  try {
    return date(new Intl.DateTimeFormat('en-CA', { timeZone: market.timeZone }).format(new Date(iso)));
  } catch {
    return date(iso);
  }
}

function renderReceipt() {
  const { list, busy, result, error } = receiptState;
  screen({
    title: 'Dodaj račun',
    back: '#/vise',
    html:
      (error ? notice(error, 'err') : '') +
      (result ? notice(`Račun iz ${result.shopName} od ${money(result.totalAmount)} je zaveden.`, 'pos') : '') +
      `<div class="card hero">
        <div class="title">Digitalni račun</div>
        <p>Izaberi screenshot računa ili PDF iz aplikacije trgovine. Ceo QR kod računa mora da se vidi.</p>
      </div>
      <label class="btn primary main" style="cursor:pointer${busy ? ';opacity:0.45' : ''}">
        ${busy ? 'Zavodim račun…' : 'Izaberi sliku ili PDF'}
        <input type="file" accept="image/*,application/pdf" data-receipt-file hidden${busy ? ' disabled' : ''}>
      </label>` +
      (list && list.length ? `<div class="section">Poslednji računi</div>
        <div class="card flush">${list.map(r => `<div class="row" style="padding:12px 16px">
          <span class="grow"><span class="name" style="display:block">${esc(r.shopName)}</span>
          <span class="muted small">${esc(receiptDay(r.issuedAt))}</span></span>
          <b>${esc(money(r.totalAmount))}</b></div>`).join('')}</div>` : '') +
      `<p class="muted small">Računi idu u potrošnju naloga, kao i oni skenirani u Android aplikaciji.</p>`
  });
}

/** Slika koja ne stane u 1 MB prepakuje se u JPEG, pa se smanjuje dok ne stane. */
async function receiptUpload(file) {
  const isPdf = file.type === 'application/pdf' || /\.pdf$/i.test(file.name);
  if (isPdf) {
    if (file.size > RECEIPT_MOST_BYTES) throw new Error('PDF je veći od 1 MB. Pošalji screenshot računa.');
    return { blob: file, name: 'racun.pdf' };
  }
  if (file.size <= RECEIPT_MOST_BYTES && (file.type === 'image/png' || file.type === 'image/jpeg')) {
    return { blob: file, name: file.type === 'image/png' ? 'racun.png' : 'racun.jpg' };
  }
  const image = await loadImage(file);
  let width = image.naturalWidth;
  let height = image.naturalHeight;
  for (let attempt = 0; attempt < 5; attempt++) {
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    context.fillStyle = '#fff';
    context.fillRect(0, 0, width, height);
    context.drawImage(image, 0, 0, width, height);
    const blob = await new Promise(done => canvas.toBlob(done, 'image/jpeg', 0.92));
    if (blob && blob.size <= RECEIPT_MOST_BYTES) return { blob, name: 'racun.jpg' };
    width = Math.round(width * 0.75);
    height = Math.round(height * 0.75);
  }
  throw new Error('Slika je prevelika. Pošalji screenshot samo dela sa QR kodom.');
}

function loadImage(file) {
  return new Promise((done, fail) => {
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => { URL.revokeObjectURL(url); done(image); };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      fail(new Error('Taj fajl ne može da se otvori. Izaberi screenshot računa ili PDF.'));
    };
    image.src = url;
  });
}

async function uploadReceipt(file) {
  if (receiptState.busy) return;
  receiptState.busy = true;
  receiptState.result = null;
  receiptState.error = null;
  renderReceipt();
  try {
    const { blob, name } = await receiptUpload(file);
    const form = new FormData();
    form.append('file', blob, name);
    receiptState.result = await api('POST', 'receipts/file', form);
    try { receiptState.list = await api('GET', 'receipts?limit=10'); } catch { /* ostaje stari spisak */ }
  } catch (error) {
    receiptState.error = error.status === 413
      ? 'Fajl je prevelik. Pošalji screenshot računa ili PDF manji od 1 MB.'
      : error.message;
  } finally {
    receiptState.busy = false;
    if (currentRoute === 'racun') renderReceipt();
  }
}

document.addEventListener('change', event => {
  if (!event.target.matches('[data-receipt-file]')) return;
  const file = event.target.files && event.target.files[0];
  event.target.value = '';
  if (file) uploadReceipt(file);
});
