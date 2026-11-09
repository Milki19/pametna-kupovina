// Ekran, donja traka sa dugmetom i kratko obaveštenje (toast).

import { esc, notice } from './format.js';
import { patchChildren } from './patch.js';

const view = document.getElementById('view');
const actionBar = document.getElementById('action');

let enterPending = null;
/** Ruta kaže kojom animacijom ulazi sledeći ekran. */
function enterNext(motion) { enterPending = motion; }
let enterTimer = null;

/**
 * Ekran sa `key` (Spisak) se pri ponovnom iscrtavanju ne gradi iznova:
 * patchChildren menja samo ono što je drugačije. Prvi put, i na drugom
 * ekranu, sadržaj se postavlja ceo, kao i do sada.
 */
function screen({ title, subtitle = null, back = null, html, action = '', key = null }) {
  document.getElementById('title').textContent = title;
  const sub = document.getElementById('subtitle');
  sub.hidden = !subtitle;
  sub.textContent = subtitle || '';
  const backButton = document.getElementById('back');
  backButton.hidden = !back;
  backButton.onclick = back ? () => { location.hash = back; } : null;
  document.body.classList.toggle('flow', Boolean(back));
  const again = key && view.dataset.screen === key;
  if (again) patchChildren(view, html); else view.innerHTML = html;
  if (key) view.dataset.screen = key; else delete view.dataset.screen;
  actionBar.hidden = !action;
  const bar = action ? `<div>${action}</div>` : '';
  if (again) patchChildren(actionBar, bar); else actionBar.innerHTML = bar;
  // Ulazak na ekran se animira jednom, kad stigne pravi sadržaj, a ne na
  // svako ponovno iscrtavanje (kucanje, štikliranje).
  if (enterPending && !view.querySelector('.spinner')) {
    const motion = enterPending;
    enterPending = null;
    view.classList.remove('enter', 'from-r', 'from-l');
    actionBar.classList.remove('enter');
    void view.offsetWidth;
    view.classList.add('enter');
    if (motion !== 'enter') view.classList.add(motion);
    if (action) actionBar.classList.add('enter');
    clearTimeout(enterTimer);
    enterTimer = setTimeout(() => { view.classList.remove('enter', 'from-r', 'from-l'); actionBar.classList.remove('enter'); }, 700);
  }
  document.title = `${title} · Pametna kupovina`;
}

let toastTimer = null;
/** Kratko obaveštenje; greška se čita odmah (role="alert") i ostaje duže. */
function toast(text, undoLabel = null, onUndo = null, error = false) {
  const box = document.getElementById('toast');
  box.setAttribute('role', error ? 'alert' : 'status');
  box.innerHTML = `<span>${esc(text)}</span>` + (undoLabel ? `<button type="button">${esc(undoLabel)}</button>` : '');
  box.hidden = false;
  if (onUndo) box.querySelector('button').onclick = () => { box.hidden = true; onUndo(); };
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.hidden = true; }, error ? 8000 : 5000);
}

/**
 * Greška iz forme ostaje u formi, uz polje na koje se odnosi (error.field je
 * ime polja), dok se forma ne pošalje ponovo. Čitač ekrana je čita odmah.
 */
function formError(form, error) {
  clearFormError(form);
  const field = error.field && form.elements.namedItem(error.field);
  const box = document.createElement('p');
  box.className = 'field-error';
  box.id = 'err-' + (form.dataset.form || 'form');
  box.setAttribute('role', 'alert');
  box.textContent = error.message;
  if (field && field.nodeType === 1) {
    field.setAttribute('aria-invalid', 'true');
    field.setAttribute('aria-describedby', box.id);
    const row = field.closest('.row');
    (row && form.contains(row) ? row : field).after(box);
    field.focus();
  } else {
    (form.querySelector('.btn-row') || form.lastElementChild).before(box);
  }
}
function clearFormError(form) {
  form.querySelectorAll('.field-error').forEach(el => el.remove());
  form.querySelectorAll('[aria-invalid]').forEach(el => { el.removeAttribute('aria-invalid'); el.removeAttribute('aria-describedby'); });
}
/** Greška vezana za jedno polje forme. */
function fieldError(field, message) {
  const error = new Error(message);
  error.field = field;
  return error;
}

function failed(title, error, retryHash) {
  screen({
    title,
    back: retryHash === '#/spisak' ? null : '#/spisak',
    html: notice(error.message, 'err') +
      `<button class="btn" data-act="retry">Pokušaj ponovo</button>`
  });
}

export { clearFormError, enterNext, failed, fieldError, formError, screen, toast, view };
