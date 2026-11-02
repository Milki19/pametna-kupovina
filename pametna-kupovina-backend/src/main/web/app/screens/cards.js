// Lojalti kartice: spisak, dodavanje i crtični kod za kasu.

import { api } from '../core/api.js';
import { esc, notice, pill, spinner } from '../core/format.js';
import { icon } from '../core/html.js';
import { screen, view } from '../core/screen.js';
import { barcodeSvg } from '../lib/barcode.js';

let cards = { list: [], adding: false, shown: null, error: null };

async function showCards() {
  screen({ title: 'Lojalti kartice', html: spinner('Učitavam kartice…') });
  try {
    cards.list = await api('GET', 'loyalty-cards');
    cards.error = null;
  } catch (error) {
    cards.error = error.message;
  }
  renderCards();
}

function codeBox(card) {
  const svg = barcodeSvg(card.cardNumber, card.barcodeFormat);
  return `<div class="code-box">${svg || '<p class="center">Ovaj oblik koda web verzija ne crta. Kasirka može da ukuca broj.</p>'}
    <div class="number">${esc(card.cardNumber)}</div></div>`;
}

function renderCards() {
  const [first, ...rest] = cards.list;
  const shown = cards.list.find(c => c.id === cards.shown);
  screen({
    title: 'Lojalti kartice',
    html:
      (cards.error ? notice(cards.error, 'err') : '') +
      `<div class="row"><span class="grow muted">Barkodovi za kasu</span>
        <button class="btn primary" data-act="add-card">${icon.add}Dodaj karticu</button></div>` +
      (cards.adding ? `<form class="card" data-form="card">
          <label class="label" for="card-name">Naziv</label>
          <input id="card-name" name="name" type="text" placeholder="npr. Moj Maxi" required>
          <label class="label" for="card-number">Broj sa kartice</label>
          <input id="card-number" name="number" inputmode="text" required>
          <select name="format" aria-label="Oblik koda">
            <option value="CODE_128">Crtični kod (najčešće)</option>
            <option value="EAN_13">EAN-13 (13 cifara)</option>
            <option value="QR_CODE">QR kod</option>
          </select>
          <div class="btn-row"><button type="button" class="btn" data-act="cancel-card">Odustani</button><button class="btn primary">Sačuvaj</button></div>
        </form>` : '') +
      (first ? `
        <button class="card hero" style="text-align:left;cursor:pointer;border-radius:24px" data-act="show-card" data-id="${first.id}">
          <span class="title">${esc(first.name)}</span>${codeBox(first)}<span class="small">Dodirni za veći kod</span>
        </button>` +
        (rest.length ? `<div class="section">Tvoj novčanik</div>` : '') +
        rest.map(c => `<button class="card row" style="text-align:left;cursor:pointer" data-act="show-card" data-id="${c.id}">
          <span class="letter">${esc(c.name.trim().charAt(0).toUpperCase())}</span>
          <span class="grow"><span class="name" style="display:block">${esc(c.name)}</span><span class="muted small">${esc(c.cardNumber)}</span></span>
          ${pill('Barkod', 'pos')}</button>`).join('')
        : `<p class="muted">Dodaj kartice koje nosiš u novčaniku pa ih na kasi otvori odavde. Kartice stoje uz nalog, kao i u Android aplikaciji.</p>`) +
      (shown ? `<div class="overlay" data-act="close-card"><div class="card" data-stop>
          <div class="title">${esc(shown.name)}</div>${codeBox(shown)}
          <p class="muted small center">Ako kod ne prolazi, kasirka može da ukuca broj.</p>
          <div class="btn-row"><button class="btn danger" data-act="delete-card" data-id="${shown.id}">Obriši karticu</button>
          <button class="btn primary" data-act="close-card">Zatvori</button></div></div></div>` : '')
  });
  const field = view.querySelector('[data-form=card] input');
  if (field) field.focus();
}

export { cards, renderCards, showCards };
