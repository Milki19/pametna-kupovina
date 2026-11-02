// Delovi HTML-a koji se ponavljaju: ikonice i oznake akcija.

import { money, shortDate } from './format.js';

// Ikonice su ukras; ime dugmeta nosi tekst ili aria-label.

const icon = {
  add: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"/></svg>',
  paste: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M19 2h-4.18C14.4.84 13.3 0 12 0S9.6.84 9.18 2H5c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-7 0c.55 0 1 .45 1 1s-.45 1-1 1-1-.45-1-1 .45-1 1-1zm7 18H5V4h2v3h10V4h2v16z"/></svg>',
  trash: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"/></svg>',
  pin: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3A8.99 8.99 0 0 0 13 3.06V1h-2v2.06A8.99 8.99 0 0 0 3.06 11H1v2h2.06A8.99 8.99 0 0 0 11 20.94V23h2v-2.06A8.99 8.99 0 0 0 20.94 13H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z"/></svg>',
  route: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M21.71 11.29l-9-9a1 1 0 0 0-1.42 0l-9 9a1 1 0 0 0 0 1.42l9 9a1 1 0 0 0 1.42 0l9-9a1 1 0 0 0 0-1.42zM14 14.5V12h-4v3H8v-4c0-.55.45-1 1-1h5V7.5l3.5 3.5-3.5 3.5z"/></svg>',
  card: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M20 4H4c-1.11 0-1.99.89-1.99 2L2 18c0 1.11.89 2 2 2h16c1.11 0 2-.89 2-2V6c0-1.11-.89-2-2-2zm0 14H4v-6h16v6zm0-10H4V6h16v2z"/></svg>',
  tag: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M21.41 11.58l-9-9C12.05 2.22 11.55 2 11 2H4c-1.1 0-2 .9-2 2v7c0 .55.22 1.05.59 1.42l9 9c.36.36.86.58 1.41.58.55 0 1.05-.22 1.41-.59l7-7c.37-.36.59-.86.59-1.41 0-.55-.23-1.06-.59-1.42zM5.5 7C4.67 7 4 6.33 4 5.5S4.67 4 5.5 4 7 4.67 7 5.5 6.33 7 5.5 7z"/></svg>',
  more: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/></svg>',
  chevron: '<svg viewBox="0 0 24 24" aria-hidden="true" focusable="false"><path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/></svg>'
};

// ---------- Akcije: popust, boja i red ispod cene ----------

/** Ceo procenat popusta, kao na serveru; null kad nema niže cene. */
function discountPercent(regular, price) {
  if (regular == null || price == null || regular <= 0 || price >= regular) return null;
  const percent = Math.round((1 - price / regular) * 100);
  return percent >= 1 ? percent : null;
}
// Iste boje kao Android: nana do 19 %, ćilibar do 39 %, crvena od 40 %.
const tier = percent => percent >= 40 ? 't-l' : percent >= 20 ? 't-m' : 't-s';
const saleBadge = (percent, large = false) =>
  `<span class="sale-badge ${tier(percent)} ${large ? 'large' : ''}" aria-label="Popust ${percent} odsto">−${percent}%</span>`;
/** Popust, precrtana redovna cena i do kada važi; sa krupnim slovima prelazi u novi red. */
const saleLine = (percent, regular, end) => `<div class="sale-line">${saleBadge(percent)}
  ${regular != null ? `<span class="strike small" aria-label="Redovna cena ${money(regular)}">${money(regular)}</span>` : ''}
  ${end ? `<span class="muted small">do ${shortDate(end)}</span>` : ''}</div>`;
const salesShortcut = () => `<a class="card row" href="#/akcije" style="text-decoration:none;color:inherit">
  <span class="letter t-m" style="border-radius:50%;background:var(--tier-bg);color:var(--tier-ink)">${icon.tag}</span>
  <span class="grow"><span class="name" style="display:block">Akcije</span>
  <span class="muted small">Šta je danas na popustu u radnjama blizu tebe</span></span>${icon.chevron}</a>`;

export { discountPercent, icon, saleBadge, saleLine, salesShortcut, tier };
