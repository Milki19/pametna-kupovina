// Više: nalog, tema, cena vremena i prevoza, o aplikaciji.

import { api } from '../core/api.js';
import { esc, market, money, number, separators, spinner } from '../core/format.js';
import { icon } from '../core/html.js';
import { currentRoute, tripFrom } from '../core/router.js';
import { failed, fieldError, screen } from '../core/screen.js';
import { appleMobile, VERSION } from '../core/session.js';
import { saved } from '../core/storage.js';
import { account, accountCard, loadAccount } from './account.js';
import { parseNumber } from './list.js';
import { forgetRecommendation } from './plan.js';

function showMore(_, __, loaded = false) {
  const standalone = navigator.standalone || matchMedia('(display-mode: standalone)').matches;
  // Prvo ono što se zna, pa sveže stanje naloga čim stigne.
  if (!loaded) loadAccount().then(() => { if (location.hash === '#/vise') showMore(null, null, true); });
  const link = (href, title, text) => `<a class="card row" href="${href}" style="text-decoration:none;color:inherit">
    <span class="grow"><span class="name" style="display:block">${title}</span><span class="muted small">${text}</span></span>${icon.chevron}</a>`;
  screen({
    title: 'Više',
    html:
      (standalone || !appleMobile ? '' : `<div class="card hero">
        <div class="title">Dodaj na početni ekran</div>
        <p>U Safari-ju dodirni <b>Podeli</b> pa <b>Dodaj na početni ekran</b>. Otvara se kao aplikacija, a spisak i kartice ostaju i kad Safari čisti stare podatke sajtova.</p>
        ${account.state?.signedIn ? '' : '<p class="small">Aplikacija na početnom ekranu ima svoje podatke, odvojene od Safari-ja. Da spisak pređe u nju, prvo se prijavi preko Google-a ovde, pa se prijavi i u njoj.</p>'}
      </div>`) +
      accountCard() +
      `<a class="card row" href="#/racun" style="text-decoration:none;color:inherit">
        <span class="grow"><span class="name" style="display:block">Dodaj račun</span>
        <span class="muted small">Screenshot ili PDF digitalnog računa</span></span>${icon.chevron}</a>` +
      link('#/potrosnja', 'Potrošnja', 'Koliko trošiš mesečno, po prodavnicama i kategorijama') +
      link('#/domacinstvo', 'Domaćinstvo', account.state?.household ? 'Delite spisak, račune i kartice' : 'Pozovi ukućanina da delite spisak') +
      link('#/vreme', 'Vreme i prevoz', 'Koliko plan računa sat u putu, kilometar kolima i stajanje') +
      themeCard() +
      `<div class="card">
        <div class="title">O aplikaciji</div>
        <p>Pametna kupovina ${VERSION}, web verzija</p>
        <p class="muted small">Cene su iz zvaničnih cenovnika trgovaca. Merodavna je cena u prodavnici.</p>
        <p class="muted small">Web verzija nema skeniranje kamerom (račun se dodaje sa screenshot-a ili PDF-a) ni obaveštenja o pojeftinjenju — to ima Android aplikacija.</p>
        <div class="btn-row"><a class="btn text" href="/privatnost">Politika privatnosti</a><a class="btn text" href="/uslovi">Uslovi korišćenja</a></div>
      </div>
      <div class="card">
        <div class="name">Broj uređaja (za pitanja o podacima)</div>
        <p class="muted small" style="overflow-wrap:anywhere">${esc(saved.get('session')?.deviceId ? 'PK-' + saved.get('session').deviceId : '…')}</p>
        <button class="btn danger" data-act="delete-account">${account.state?.household ? 'Izađi iz domaćinstva i obriši podatke' : 'Obriši moje podatke'}</button>
      </div>`
  });
}

const themes = [[null, 'Sistemska'], ['light', 'Svetla'], ['dark', 'Tamna'], ['black', 'Crna']];

function themeCard() {
  const current = saved.get('theme', null);
  return `<div class="card">
    <div class="title">Tema</div>
    <div class="theme-choice" role="group" aria-label="Tema">
      ${themes.map(([value, label]) => `<button class="chip" data-act="theme" data-value="${value || ''}" aria-pressed="${current === value}">${label}</button>`).join('')}
    </div>
    <p class="muted small">Sistemska prati telefon. Crna štedi bateriju na OLED ekranima.</p>
  </div>`;
}

// Cena vremena, kilometra i stajanja: plan njima meri da li se isplati vožnja do jeftinije radnje.
let tripCosts = null;

async function showTripCosts() {
  screen({ title: 'Vreme i prevoz', back: tripFrom, html: spinner('Učitavam…') });
  try {
    tripCosts = await api('GET', 'accounts/me/trip-costs');
  } catch (error) {
    if (currentRoute === 'vreme') failed('Vreme i prevoz', error, '#/vreme');
    return;
  }
  // Ko je u međuvremenu otišao na drugi ekran, ostaje tamo.
  if (currentRoute === 'vreme') renderTripCosts();
}

// Iznos u polju bez razdvajanja hiljada, da se pročita isto kao što je upisan.
const tripInput = value => number(value, market.currencyMinorUnits).split(separators.group).join('');

// „1.000“ je u Srbiji hiljada: tačka razdvaja hiljade samo kad grupe stvarno imaju po tri cifre.
function parseTripAmount(text) {
  const raw = String(text ?? '').trim().replace(/\s/g, '');
  const escape = c => c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const grouped = new RegExp(`^\\d{1,3}(${escape(separators.group)}\\d{3})+(${escape(separators.decimal)}\\d*)?$`).test(raw);
  return parseNumber(grouped ? raw.split(separators.group).join('') : raw);
}

function renderTripCosts(message = null) {
  const t = tripCosts;
  screen({
    title: 'Vreme i prevoz',
    back: tripFrom,
    html: `
      <div class="card soft">
        <div class="title">Šta ti vredi više: vreme ili ušteda</div>
        <p class="muted small">Plan sabira korpu, gorivo, vreme u putu i stajanja. Jeftinija korpa dalje od tebe pobeđuje samo ako ušteda pokrije put. Peške kilometar ne košta, a sat hoda se računa po ovoj ceni; na planu peške vidiš korpu i minute hoda.</p>
      </div>
      <form class="card" data-form="trip-costs">
        <label class="label" for="trip-hour">Sat u putu (${esc(t.currency)})</label>
        <input id="trip-hour" name="hour" inputmode="decimal" value="${esc(tripInput(t.valuePerHour))}" required>
        <label class="label" for="trip-km">Kilometar kolima (${esc(t.currency)})</label>
        <input id="trip-km" name="km" inputmode="decimal" value="${esc(tripInput(t.costPerKm))}" required>
        <p class="muted small">Gorivo i trošak auta. Peške kilometar ne košta ništa.</p>
        <label class="label" for="trip-stop">Jedno stajanje (${esc(t.currency)})</label>
        <input id="trip-stop" name="stop" inputmode="decimal" value="${esc(tripInput(t.costPerStop))}" required>
        <p class="muted small">Podrazumevano je ${money(t.defaultValuePerHour)} po satu, ${money(t.defaultCostPerKm)} po kilometru i ${money(t.defaultCostPerStop)} po stajanju. Veći iznos znači manje vožnje i manje radnji, a 0 znači da plan to ne računa.</p>
        <div class="btn-row">${t.customized ? '<button type="button" class="btn" data-act="trip-reset">Vrati podrazumevano</button>' : ''}<button class="btn primary">Sačuvaj</button></div>
      </form>
      ${message ? `<div class="notice pos" role="status" data-trip-saved>${esc(message)}</div>` : ''}`
  });
}

async function saveTripCosts(valuePerHour, costPerStop, costPerKm) {
  tripCosts = await api('PUT', 'accounts/me/trip-costs', { valuePerHour, costPerStop, costPerKm });
  // Plan je računat po staroj ceni.
  forgetRecommendation();
  if (currentRoute !== 'vreme') return;
  renderTripCosts(tripCosts.customized
    ? `Sačuvano. Plan računa ${money(tripCosts.valuePerHour)} po satu, ${money(tripCosts.costPerKm)} po kilometru i ${money(tripCosts.costPerStop)} po stajanju.`
    : 'Sačuvano. Plan opet računa podrazumevano vreme, kilometar i stajanja.');
}

function tripAmount(data, name, max) {
  const value = parseTripAmount(data.get(name));
  if (value == null || value < 0 || value > max) {
    throw fieldError(name, `Upiši iznos od 0 do ${tripInput(max)} ${market.currency}.`);
  }
  return value;
}

export { saveTripCosts, showMore, showTripCosts, tripAmount, tripCosts };
