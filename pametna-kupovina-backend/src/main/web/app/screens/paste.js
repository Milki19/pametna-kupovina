// Nalepi spisak: pregled kako će se pročitati svaki red pre dodavanja.

import { api, listRef } from '../core/api.js';
import { counted, decimal, esc, plural } from '../core/format.js';
import { toast, view } from '../core/screen.js';
import { change, listState, refresh, renderList, saidOffline } from './list.js';

// Isto čitanje kao PastedListParser u Android aplikaciji (i ShoppingListTextParser
// na serveru): „Kupi:“ je naslov, „(3)“ i „2x“ su količina, „za malog“ je beleška,
// a red bez ijednog slova server ne prima. Samo za pregled; dodaje server.
const paste = (() => {
  const number = String.raw`\d+(?:[.,]\d+)?`;
  const leading = new RegExp(String.raw`^(${number})\s*[x×]\s+(.+)$`, 'iu');
  const trailing = new RegExp(String.raw`^(.+?)\s+[x×]\s*(${number})$`, 'iu');
  // „grcki jogurt 3x“, „jogurt 3 puta“: broj pakovanja posle naziva (rečnik jedinica, V157).
  const trailingTimes = new RegExp(String.raw`^(.+?)\s+(${number})\s*(?:[x×]|puta)$`, 'iu');
  const bullet = /^[\s*•·▪◦-]+/u;
  const emoji = /[\u{1F000}-\u{1FAFF}\u{2600}-\u{27BF}\u{2B00}-\u{2BFF}\u{2300}-\u{23FF}\u{FE0E}\u{FE0F}\u{200D}\u{20E3}\u{E0020}-\u{E007F}]+/gu;
  const heading = /^[^0-9:]{1,40}:$/u;
  const headingBefore = /^(?:kupi(?:ti)?|spisak|lista|treba|potrebno|nabavka|market|prodavnica|купи(?:ти)?|списак|листа|треба|потребно|набавка)\s*:\s*(?=\S)/iu;
  const approximately = /(?<![\p{L}0-9])(?:oko|otprilike|cca\.?|около|отприлике|~)\s*(?=[0-9])/giu;
  const inBrackets = new RegExp(String.raw`\s*\(\s*(${number})\s*(?:kom(?:ad(?:a|i)?)?\.?|ком(?:ад(?:а|и)?)?\.?|pcs)?\s*\)`, 'iu');
  const noteWords = '(?:malog|malu|male|mamu|tatu|baku|deku|dedu|decu|dete|sina|[cć]erku|[žz]enu|mu[žz]a'
    + '|goste|gosta|kom[šs]ij[ue]|prijatelje|kuma|kumu|brata|sestru|nju|njega|sebe'
    + '|utakmicu|ro[đd]j?endan|slavu|[žz]urku|proslavu|vikend|put|posao|[šs]kolu'
    + '|vrti[ćc]|doru[čc]ak|ru[čc]ak|ve[čc]eru|u[žz]inu|sutra|danas|praznik'
    + '|novu godinu|bo[žz]i[ćc]|uskrs)';
  const note = new RegExp(String.raw`\s+(za\s+${noteWords}(?![\p{L}])[^0-9()]*?)(?=\s+[0-9]|\s*$)`, 'iu');
  const noteInBrackets = /\s*\((\s*za\s+[^()]+?)\s*\)/iu;
  const separator = /\s*[,;]\s+|\s*;\s*/u;
  const word = /\p{L}{2,}/u;
  const letter = /\p{L}/u;

  const withoutEmoji = text => {
    const stripped = text.replace(emoji, ' ');
    return stripped === text ? text : stripped.replace(/\s{2,}/gu, ' ').trim();
  };
  const isHeading = line => {
    const clean = withoutEmoji(line).replace(bullet, '').trim();
    return heading.test(clean) && word.test(clean);
  };
  const cut = (text, match) => (text.slice(0, match.index) + text.slice(match.index + match[0].length)).trim();
  function withNote(line) {
    let rest = line;
    let found = null;
    const bracketed = noteInBrackets.exec(rest);
    if (bracketed) { found = bracketed[1].trim(); rest = cut(rest, bracketed); }
    const plain = note.exec(rest);
    if (plain && word.test(rest.slice(0, plain.index))) { found = found ?? plain[1].trim(); rest = cut(rest, plain); }
    return [rest, found];
  }
  function itemsOnLine(line) {
    const parts = line.split(separator);
    if (parts.length < 2 || parts.some(part => part.trim() && !word.test(part))) return [line];
    return parts.map(part => part.trim()).filter(Boolean);
  }
  const toQuantity = text => { const value = Number(text.replace(',', '.')); return Number.isFinite(value) ? value : 1; };
  function readLine(raw) {
    const written = withoutEmoji(raw).replace(bullet, '').trim();
    const [rest, found] = withNote(written.replace(approximately, ''));
    const count = inBrackets.exec(rest);
    const clean = count ? `${cut(rest, count)} x${count[1]}` : rest;
    const first = leading.exec(clean);
    const last = first ? null : trailing.exec(clean) || trailingTimes.exec(clean);
    const name = (first ? first[2] : last ? last[1] : clean).trim();
    const quantity = first ? toQuantity(first[1]) : last ? toQuantity(last[2]) : 1;
    if (!name) return null;
    if (!letter.test(name)) return { text: raw, skipped: 'letters' };
    return { text: raw, name, quantity, note: found };
  }

  /** Svaki red kako će biti dodat: { name, quantity, note } ili { text, skipped }. */
  return text => String(text || '').split(/\r?\n/).map(line => line.trim()).filter(Boolean).flatMap(line => {
    if (isHeading(line)) return [{ text: line, skipped: 'heading' }];
    const match = headingBefore.exec(line);
    const rest = match ? line.slice(match[0].length) : line;
    const head = match ? [{ text: match[0].trim(), skipped: 'heading' }] : [];
    return head.concat(itemsOnLine(rest).map(readLine).filter(Boolean));
  });
})();

/** Ispod polja: koliko će biti dodato i kako je pročitan svaki red. */
function pastePreviewHtml(text) {
  const lines = paste(text);
  const count = lines.filter(line => !line.skipped).length;
  return `<p class="label">${count ? `Biće dodato: ${counted(count, 'stavka', 'stavke', 'stavki')}` : 'Nijedna stavka još nije upisana.'}</p>`
    + (lines.length ? `<ul class="paste-preview">${lines.map(line => line.skipped
      ? `<li class="skipped"><div class="grow"><s>${esc(line.text)}</s>
           <div class="muted small">${line.skipped === 'heading' ? 'naslov, ne dodaje se' : 'nema slova, ne dodaje se'}</div></div></li>`
      : `<li><div class="grow"><div>${esc(line.name)}</div>${line.note ? `<div class="muted small">Beleška: ${esc(line.note)}</div>` : ''}</div>
           ${line.quantity !== 1 ? `<b class="amount">× ${esc(decimal(line.quantity))}</b>` : ''}</li>`).join('')}</ul>` : '');
}

/** Jedna stavka: „Dodaj“; više njih: „Dodaj sve (3)“. */
const addLabel = count => count > 1 ? `Dodaj sve (${count})` : 'Dodaj';
const itemCount = text => paste(text).filter(line => !line.skipped).length;

/** Pregled se vidi tek kad ima šta da pokaže: više stavki, naslov, red bez slova ili beleška. */
function updatePastePreview(field) {
  const lines = paste(field.value);
  const count = lines.filter(line => !line.skipped).length;
  const box = view.querySelector('#paste-preview');
  if (box) box.innerHTML = count > 1 || lines.some(line => line.skipped || line.note) ? pastePreviewHtml(field.value) : '';
  const submit = field.form?.querySelector('button:not([type=button])');
  if (submit) {
    submit.disabled = !count;
    submit.textContent = addLabel(count);
  }
}

// Pregled se osvežava dok se kuca. Ovde, a ne u Cenama, jer je Nalepi deo
// prvog ekrana, a Cene se učitavaju tek kad zatrebaju.
document.addEventListener('input', event => {
  if (event.target.id === 'many') updatePastePreview(event.target);
});

/** Više stavki na spisak koji nije prazan: prvo pitanje „Dodaj na spisak“ ili „Zameni spisak“. */
async function submitPaste(text) {
  if (listState.list.items.length && itemCount(text) > 1) {
    listState.pasteAsk = text;
    renderList();
    view.querySelector('[data-act=paste-add]')?.focus();
    return;
  }
  await addText(text);
}

function pasteAskCard() {
  if (listState.adding !== 'paste' || listState.pasteAsk == null) return '';
  const count = listState.list.items.length;
  return `<div class="card" role="group" aria-labelledby="paste-ask-title">
      <div class="name" id="paste-ask-title">Na spisku već ima ${counted(count, 'stavka', 'stavke', 'stavki')}</div>
      <p class="muted">Nalepljene stavke možeš da dodaš uz postojeće ili da njima zameniš ceo spisak.</p>
      <div class="btn-row">
        <button class="btn" data-act="paste-ask-cancel">Odustani</button>
        <button class="btn danger" data-act="paste-replace">Zameni spisak</button>
        <button class="btn primary" data-act="paste-add">Dodaj na spisak</button>
      </div></div>`;
}

/** Upravo nalepljena stavka čiju vrstu server nije prepoznao. */
const pastedUnknown = item => listState.pastedUnknown.has(item.id) && item.matchingStatus !== 'CONFIRMED';

const rejectedMark = id => listState.rejected ? `aria-invalid="true" aria-describedby="${id}-error" ` : '';

/** Redovi koje server nije dodao ostaju u polju, a razlog svakog piše ispod njega. */
function rejectedNote() {
  const lines = listState.rejected?.lines;
  if (!lines?.length) return '';
  return `<p class="field-error" id="many-error" role="alert">${
    lines.map(line => esc(`„${line.text}“: ${line.message}`)).join('<br>')}</p>`;
}

async function addText(text) {
  const listId = listRef(listState.list);
  let result;
  try {
    result = await api('POST', `shopping-lists/${listId}/items/paste`, { text });
  } catch (error) {
    if (!error.offline) throw error;
    return addWithoutNetwork(listId, text);
  }
  const rejected = result.rejectedLines || [];
  // Nalepljen spisak (više stavki, i posle „Dodaj na spisak“ / „Zameni spisak“), ne jedna stavka.
  const pasted = itemCount(text) > 1;
  if (pasted) {
    listState.pastedUnknown = new Set((result.items || [])
      .filter(item => item.matchingStatus !== 'CONFIRMED').map(item => item.id));
  }
  listState.rejected = rejected.length ? { text: rejected.map(line => line.text).join('\n'), lines: rejected } : null;
  if (!rejected.length) listState.adding = null;
  // Neka ranija izmena još čeka vezu: dodato se vidi sa servera kad i ona stigne.
  listState.list.items.push(...(result.items || []).filter(item => !listState.list.items.some(i => i.id === item.id)));
  await refresh(listId);
  renderList();
  const unknown = listState.pastedUnknown.size;
  const read = !pasted || !result.createdCount ? ''
    : !unknown ? ' Sve je prepoznato.'
      : ` Prepoznato ${result.createdCount - unknown}, a ${unknown} ${plural(unknown, 'nije: stoji', 'nisu: stoje', 'nisu: stoje')}`
        + ' na vrhu i traži se po nazivu kad klikneš Izračunaj.';
  const added = (result.createdCount === 1 ? 'Dodato.'
    : result.createdCount ? `Dodato: ${counted(result.createdCount, 'stavka', 'stavke', 'stavki')}.` : 'Nema stavki za dodavanje.') + read;
  toast(rejected.length ? `${added} Nije dodato: ${rejected.length}, razlog je ispod polja.` : added);
}

/**
 * Bez veze: nalepljeno čeka u redu i šalje se serveru kakvo je, a dotle je na
 * spisku onako kako ga čita pregled (naziv, broj, beleška), sa „Čeka vezu“.
 * Server ga posle čita svojim pravilima, isto kao da je stiglo odmah.
 */
async function addWithoutNetwork(listId, text) {
  await change('POST', `shopping-lists/${listId}/items/paste`, { text });
  const lines = paste(text).filter(line => !line.skipped);
  const stamp = Date.now();
  listState.list.items.push(...lines.map((line, i) => ({
    id: -(stamp + i), name: line.name, note: line.note, quantity: line.quantity,
    matchingRule: 'FLEXIBLE_CATEGORY', flexibleConstraints: null, waiting: 'paste'
  })));
  listState.rejected = null;
  listState.adding = null;
  listState.offline = true;
  renderList();
  toast(saidOffline(lines.length === 1 ? 'Dodato na telefonu.' : `Dodato na telefonu: ${counted(lines.length, 'stavka', 'stavke', 'stavki')}.`));
}

export { addLabel, addText, paste, pasteAskCard, pastedUnknown, pastePreviewHtml, rejectedMark, rejectedNote, submitPaste, updatePastePreview };
