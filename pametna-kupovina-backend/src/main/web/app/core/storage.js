// Čuvanje u pregledaču (localStorage) i ključ uređaja.

const saved = {
  get(key, fallback = null) {
    try {
      const value = localStorage.getItem('pk.' + key);
      return value == null ? fallback : JSON.parse(value);
    } catch { return fallback; }
  },
  set(key, value) {
    try {
      if (value == null) localStorage.removeItem('pk.' + key);
      else localStorage.setItem('pk.' + key, JSON.stringify(value));
    } catch { /* privatni prozor: radi i bez pamćenja */ }
  }
};

// Ključ je jedini put nazad do naloga i spiska, pa se nikad ne menja sam od
// sebe. Zapis koji nije JSON (ručno izmenjen, npr. bez navodnika) čita se
// kakav jeste, a kad pregledač ništa ne pamti, ključ ostaje bar u memoriji.
let tokenInMemory = null;
function clientToken() {
  let token = saved.get('token');
  if (token == null) {
    try {
      const raw = (localStorage.getItem('pk.token') || '').trim();
      if (/^[A-Za-z0-9_-]{8,100}$/.test(raw)) token = raw;
    } catch { /* privatni prozor */ }
  }
  token = token == null ? '' : String(token).trim();
  if (!token && !storageWorks()) token = tokenInMemory || '';
  if (!token) {
    token = crypto.randomUUID ? crypto.randomUUID()
      : [...crypto.getRandomValues(new Uint8Array(16))].map(b => b.toString(16).padStart(2, '0')).join('');
  }
  if (saved.get('token') !== token) saved.set('token', token);
  tokenInMemory = token;
  return token;
}

function storageWorks() {
  try {
    localStorage.setItem('pk.probe', '1');
    localStorage.removeItem('pk.probe');
    return true;
  } catch { return false; }
}

export { clientToken, saved };
