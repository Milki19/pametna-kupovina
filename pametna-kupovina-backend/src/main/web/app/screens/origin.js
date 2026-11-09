// Korak 2: polazna tačka (lokacija, adresa iz pretrage ili poslednja).

import { api } from '../core/api.js';
import { esc, notice } from '../core/format.js';
import { icon } from '../core/html.js';
import { screen, view } from '../core/screen.js';
import { browserName, locationDenied } from '../core/session.js';
import { saved } from '../core/storage.js';

const coordinates = (lat, lng) => `${lat.toFixed(4)}, ${lng.toFixed(4)}`;
const originLabel = origin => origin.label || coordinates(origin.lat, origin.lng);

// Adrese iz pretrage ostaju na ekranu dok se ne izabere jedna.
let places = { query: '', results: null };

function showLocation(message = null, tone = 'err') {
  const last = saved.get('origin');
  const placeField = view.querySelector('[data-form=place] input[name=q]');
  if (placeField) places.query = placeField.value;
  screen({
    title: 'Odakle krećeš',
    back: '#/provera',
    html: `
      <div class="card soft">
        <div class="label accent">Korak 2 od 3</div>
        <div class="title">Polazna tačka</div>
        <p class="muted small">Put računamo od polazne tačke do prodavnica u blizini.</p>
      </div>
      <form class="card" data-form="place" role="search">
        <label class="label" for="place">Adresa ili kraj</label>
        <div class="row"><input id="place" name="q" type="search" enterkeyhint="search" autocomplete="street-address"
          placeholder="npr. Bulevar oslobođenja 10, Novi Sad" value="${esc(places.query)}" style="flex:1">
          <button class="btn primary">Traži</button></div>
      </form>
      <div class="label">Kako ideš do prodavnica</div>
      <div class="chips" role="group" aria-label="Kako ideš do prodavnica">
        <button class="chip" data-act="travel" data-value="DRIVING" aria-pressed="${!saved.get('walking', false)}">Kolima</button>
        <button class="chip" data-act="travel" data-value="WALKING" aria-pressed="${saved.get('walking', false)}">Peške</button>
      </div>
      ${saved.get('walking', false) ? '<p class="muted small">Peške: radnje do 3 km, put ne košta gorivo, a vreme se računa hodom.</p>' : ''}
      <a class="btn text small" href="#/vreme">Promeni cenu vremena i prevoza</a>
      ${places.results ? (places.results.length ? `<div class="card flush">${places.results.map((p, index) => `
        <button class="list-row row" style="width:100%;text-align:left;cursor:pointer;background:none;border:0" data-act="use-place" data-index="${index}" aria-label="Polazna tačka: ${esc(p.name)}">
          <span style="color:var(--primary)">${icon.pin}</span><span class="grow">${esc(p.name)}</span>${icon.chevron}</button>`).join('')}</div>`
        : notice('Ne nalazimo tu adresu. Dodaj grad ili probaj sa nazivom kraja.', 'warn')) : ''}
      <button class="card row" style="text-align:left;cursor:pointer" data-act="locate">
        <span class="letter" style="border-radius:50%;background:var(--mint);color:var(--on-mint)">${icon.pin}</span>
        <span class="grow"><span class="name" style="display:block">Koristi trenutnu lokaciju</span>
        <span class="muted small">${browserName} će pitati za dozvolu</span></span>${icon.chevron}
      </button>
      ${last ? `<button class="card row" style="text-align:left;cursor:pointer" data-act="use-last">
        <span class="grow"><span class="label">Poslednja polazna tačka</span><span style="display:block">${esc(originLabel(last))}</span></span>
        <span class="btn">Koristi</span></button>` : ''}
      ${message ? notice(message, tone) : ''}
      <p class="muted small">Lokacija služi samo za ovo računanje i za redosled pretrage. Ne prati se u pozadini. Za plan je server ne čuva; za pretragu i Akcije je zaokružena na 1 km, čuva se 5 minuta. Adresu server traži preko OpenStreetMap-a i ne upisuje je.</p>`
  });
}

/** Adresa tačke („Bulevar oslobođenja 10, Novi Sad“) umesto brojeva; null kad je nema. */
async function addressOf(lat, lng) {
  try {
    const place = await api('GET', `places/reverse?latitude=${lat}&longitude=${lng}`);
    return place?.name || null;
  } catch {
    return null;
  }
}

async function useOrigin(lat, lng, label = null) {
  label = label || await addressOf(lat, lng);
  saved.set('origin', label ? { lat, lng, label } : { lat, lng });
  places = { query: '', results: null };
  location.hash = '#/preporuke';
}

function locate() {
  if (!navigator.geolocation) return showLocation('Ovaj pregledač ne daje lokaciju. Upiši adresu ili kraj.');
  showLocation('Tražim lokaciju…', '');
  navigator.geolocation.getCurrentPosition(
    position => useOrigin(position.coords.latitude, position.coords.longitude),
    error => showLocation(error.code === 1
      ? locationDenied()
      : 'Lokacija trenutno nije dostupna. Probaj ponovo ili upiši adresu.'),
    { enableHighAccuracy: false, timeout: 15000, maximumAge: 300000 }
  );
}

export { locate, originLabel, places, showLocation, useOrigin };
