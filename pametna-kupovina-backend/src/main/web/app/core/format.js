// Oblik brojeva, novca i datuma po tržištu naloga (1.086,92 RSD, 24.09.2026.).

import { api } from './api.js';
import { saved } from './storage.js';

// Tržište naloga: valuta i znaci za hiljade i decimale. Srbija dok server ne
// kaže drugačije, i poslednje poznato kad nema veze.
const DEFAULT_MARKET = { currency: 'RSD', currencyMinorUnits: 2, locale: 'sr-Latn-RS', timeZone: 'Europe/Belgrade' };
let market = saved.get('market', DEFAULT_MARKET);
let separators = separatorsOf(market.locale);

function separatorsOf(locale) {
  try {
    const parts = new Intl.NumberFormat(locale).formatToParts(12345.6);
    return {
      group: parts.find(part => part.type === 'group')?.value ?? '.',
      decimal: parts.find(part => part.type === 'decimal')?.value ?? ','
    };
  } catch { return { group: '.', decimal: ',' }; }
}

function useMarket(value) {
  market = value;
  separators = separatorsOf(market.locale);
  saved.set('market', market);
}

async function refreshMarket() {
  try {
    const account = await api('GET', 'accounts/me');
    if (!account?.market || JSON.stringify(account.market) === JSON.stringify(market)) return false;
    useMarket(account.market);
    return true;
  } catch { return false; }
}

function number(value, decimals) {
  const [whole, part] = Math.abs(value).toFixed(decimals).split('.');
  return (value < 0 ? '−' : '') + whole.replace(/\B(?=(\d{3})+(?!\d))/g, separators.group)
    + (part ? separators.decimal + part : '');
}
const money = value => number(value, market.currencyMinorUnits) + ' ' + market.currency;
const whole = value => number(Math.round(value), 0);
function decimal(value, max = 2) {
  const text = number(value, max);
  return text.includes(separators.decimal)
    ? text.replace(/0+$/, '').replace(new RegExp('\\' + separators.decimal + '$'), '')
    : text;
}
function date(iso) {
  const [y, m, d] = String(iso).slice(0, 10).split('-');
  return `${d}.${m}.${y}.`;
}
const shortDate = iso => date(iso).slice(0, 6);
// Cena starija od tri dana dobija datum uz stavku; svežu ne treba naglašavati.
function stalePrice(priceDate, requestedDate) {
  if (!priceDate || !requestedDate) return false;
  const day = 24 * 60 * 60 * 1000;
  return Date.parse(requestedDate.slice(0, 10)) - Date.parse(priceDate.slice(0, 10)) > 3 * day;
}
function plural(count, one, few, many) {
  const n10 = count % 10, n100 = count % 100;
  if (n10 === 1 && n100 !== 11) return one;
  if (n10 >= 2 && n10 <= 4 && (n100 < 12 || n100 > 14)) return few;
  return many;
}
const counted = (count, one, few, many) => `${count} ${plural(count, one, few, many)}`;
function amountLabel(value, unit, packageCount = 1) {
  if (packageCount > 1) return `${packageCount} × ${amountLabel(value / packageCount, unit)}`;
  const large = (unit === 'g' || unit === 'ml') && value >= 1000;
  const shown = decimal(large ? value / 1000 : value, 3);
  const label = unit === 'g' ? (large ? 'kg' : 'g') : unit === 'ml' ? (large ? 'l' : 'ml') : unit === 'piece' ? 'kom' : (unit || '?');
  return `${shown} ${label}`;
}
function distance(km) {
  return km < 1 ? `${Math.round(km * 100) * 10} m` : `${decimal(km, 1)} km`;
}
function duration(seconds) {
  // Kratak put je „1 min“, ne „0 min“.
  const minutes = seconds > 0 ? Math.max(1, Math.floor((seconds + 30) / 60)) : 0;
  return minutes >= 60 ? `${Math.floor(minutes / 60)} h ${minutes % 60} min` : `${minutes} min`;
}
const perUnit = unit => unit === 'g' ? 'kg' : unit === 'ml' ? 'l' : 'kom';

const esc = value => String(value ?? '').replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`);
const pill = (text, tone = '') => `<span class="pill ${tone}">${esc(text)}</span>`;
const notice = (text, tone = '') => `<div class="notice ${tone}" ${tone === 'err' ? 'role="alert"' : ''}>${esc(text)}</div>`;
const spinner = text => `<div class="spinner"></div><p class="center muted">${esc(text)}</p>`;

export { amountLabel, counted, date, decimal, distance, duration, esc, market, money, notice, number, perUnit, pill, plural, refreshMarket, separators, shortDate, spinner, stalePrice, useMarket, whole };
