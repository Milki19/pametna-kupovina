(() => {
  const API = '/api/v1/imports/quality';
  const KEY_STORAGE = 'pk-admin-key';
  const money = new Intl.NumberFormat('sr-RS', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
  const state = { reportStatus: 'NEW', type: 'SALT', typeLabel: 'so' };

  function adminKey() {
    try { return sessionStorage.getItem(KEY_STORAGE) || ''; } catch (e) { return ''; }
  }

  async function api(path, options = {}) {
    const headers = { 'Content-Type': 'application/json' };
    const key = adminKey();
    if (key) headers['X-Admin-Key'] = key;
    const response = await fetch(API + path, { ...options, headers });
    if (response.status === 401 || response.status === 503) {
      document.getElementById('key-banner').hidden = false;
      throw new Error('Potreban je administratorski ključ.');
    }
    const body = await response.json().catch(() => null);
    if (!response.ok) {
      throw new Error((body && body.message) || 'Zahtev nije uspeo (' + response.status + ').');
    }
    return body;
  }

  function el(tag, attributes = {}, ...children) {
    const node = document.createElement(tag);
    for (const [name, value] of Object.entries(attributes)) {
      if (name === 'class') node.className = value;
      else if (name.startsWith('on')) node.addEventListener(name.slice(2), value);
      else node.setAttribute(name, value);
    }
    for (const child of children) {
      if (child == null) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }

  function showError(error) {
    const banner = document.getElementById('error');
    banner.textContent = error ? error.message : '';
    banner.hidden = !error;
  }

  let toastTimer;
  function toast(message) {
    const node = document.getElementById('toast');
    node.textContent = message;
    node.style.display = 'block';
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => { node.style.display = 'none'; }, 3500);
  }

  function amount(value, unit) {
    if (value == null) return '';
    const large = (unit === 'g' || unit === 'ml') && value >= 1000;
    const number = (large ? value / 1000 : value).toLocaleString('sr-RS');
    const label = unit === 'g' ? (large ? 'kg' : 'g') : unit === 'ml' ? (large ? 'l' : 'ml') : unit === 'piece' ? 'kom' : (unit || '');
    return number + ' ' + label;
  }

  async function act(button, request, onDone) {
    const buttons = button.parentElement.querySelectorAll('button');
    buttons.forEach(b => { b.disabled = true; });
    try {
      const result = await request();
      showError(null);
      onDone(result);
    } catch (error) {
      showError(error);
      buttons.forEach(b => { b.disabled = false; });
    }
  }

  // Isti proizvodi?
  const DUPLICATES_PAGE = 50;
  const selectedPairs = new Map();

  function pairsWord(count) {
    const lastTwo = count % 100;
    const last = count % 10;
    if (last === 1 && lastTwo !== 11) return 'par';
    if (last >= 2 && last <= 4 && (lastTwo < 12 || lastTwo > 14)) return 'para';
    return 'parova';
  }

  async function loadDuplicateCount() {
    try {
      const { total } = await api('/duplicates/count');
      const shown = document.querySelectorAll('#duplicates .card').length;
      document.getElementById('count-duplicates').textContent = total ? '(' + total + ')' : '';
      document.getElementById('duplicates-total').textContent = total
        ? 'Ukupno ' + total + ' ' + pairsWord(total) + ' čeka odluku, ovde je prikazano ' + shown + '.'
        : '';
    } catch (error) { /* the list shows the error */ }
  }

  function updateSelection() {
    const count = selectedPairs.size;
    const cards = document.querySelectorAll('#duplicates .card');
    document.getElementById('duplicates-bar').hidden = cards.length === 0;
    document.getElementById('duplicates-selected').textContent = count
      ? 'Izabrano: ' + count + ' ' + pairsWord(count)
      : 'Ništa nije izabrano';
    const same = document.getElementById('duplicates-same');
    const different = document.getElementById('duplicates-different');
    same.disabled = count === 0;
    different.disabled = count === 0;
    same.textContent = count ? 'Svi isti proizvod (' + count + ')' : 'Isti proizvod';
    different.textContent = count ? 'Svi različiti (' + count + ')' : 'Različiti';
    const all = document.getElementById('duplicates-all');
    all.checked = cards.length > 0 && count === cards.length;
    all.indeterminate = count > 0 && count < cards.length;
  }

  async function loadDuplicates() {
    const container = document.getElementById('duplicates');
    container.textContent = 'Učitavam…';
    selectedPairs.clear();
    try {
      const pairs = await api('/duplicates?limit=' + DUPLICATES_PAGE);
      showError(null);
      container.textContent = '';
      if (!pairs.length) {
        container.append(el('p', { class: 'empty' }, 'Nema parova za pregled.'));
      }
      for (const pair of pairs) container.append(duplicateCard(pair));
    } catch (error) {
      container.textContent = '';
      showError(error);
    }
    updateSelection();
    loadDuplicateCount();
  }

  async function decideSelected(same, button) {
    const ids = [...selectedPairs.keys()];
    if (!ids.length) return;
    const bar = document.getElementById('duplicates-bar');
    bar.querySelectorAll('button, input').forEach(control => { control.disabled = true; });
    try {
      const result = await api('/duplicates/decisions', {
        method: 'POST',
        body: JSON.stringify({ suggestionIds: ids, same })
      });
      showError(null);
      for (const card of selectedPairs.values()) card.remove();
      selectedPairs.clear();
      toast(result.message);
    } catch (error) {
      showError(error);
    }
    bar.querySelectorAll('input').forEach(control => { control.disabled = false; });
    if (!document.querySelector('#duplicates .card')) {
      loadDuplicates();
      return;
    }
    updateSelection();
    loadDuplicateCount();
  }

  function side(item) {
    return el('div', { class: 'side' },
      el('div', { class: 'name' }, item.name),
      el('div', { class: 'meta' }, item.retailers || 'nema lanaca'),
      item.lowestPrice != null ? el('div', { class: 'meta' }, 'od ' + money.format(item.lowestPrice) + ' ' + item.currency) : null);
  }

  function duplicateCard(pair) {
    const card = el('div', { class: 'card' });
    const checkbox = el('input', { type: 'checkbox', 'aria-label': 'Izaberi ovaj par' });
    checkbox.addEventListener('change', () => {
      if (checkbox.checked) selectedPairs.set(pair.id, card);
      else selectedPairs.delete(pair.id);
      card.classList.toggle('selected', checkbox.checked);
      updateSelection();
    });
    const decide = same => button => act(button,
      () => api('/duplicates/' + pair.id, { method: 'POST', body: JSON.stringify({ same }) }),
      result => {
        selectedPairs.delete(pair.id);
        card.remove();
        toast(result.message);
        updateSelection();
        loadDuplicateCount();
      });
    const sameButton = el('button', { class: 'primary' }, 'Isti proizvod');
    const differentButton = el('button', { class: 'secondary' }, 'Različiti');
    sameButton.addEventListener('click', () => decide(true)(sameButton));
    differentButton.addEventListener('click', () => decide(false)(differentButton));
    card.append(
      el('div', { class: 'card-head' },
        el('label', {}, checkbox, [pair.brand, amount(pair.quantityValue, pair.baseUnit)].filter(Boolean).join(' · ')),
        el('span', {}, similarity(pair))),
      el('div', { class: 'pair' }, side(pair.left), side(pair.right)),
      el('div', { class: 'actions' }, differentButton, sameButton));
    return card;
  }

  // Slova (pg_trgm) i značenje naziva (V135), kad je bodovano.
  function similarity(pair) {
    const number = value => Number(value).toLocaleString('sr-RS', { maximumFractionDigits: 2 });
    const letters = 'sličnost naziva ' + number(pair.score);
    return pair.meaningScore == null ? letters : letters + ' · značenja ' + number(pair.meaningScore);
  }

  // Prijave
  const REASONS = { WRONG_PRICE: 'Cena nije tačna', NOT_SAME_PRODUCT: 'Ovo nisu isti proizvodi', OTHER: 'Nešto drugo' };

  async function loadReports() {
    const container = document.getElementById('reports');
    container.textContent = 'Učitavam…';
    try {
      const reports = await api('/reports?limit=200&status=' + state.reportStatus);
      showError(null);
      container.textContent = '';
      if (state.reportStatus === 'NEW') {
        document.getElementById('count-reports').textContent = reports.length ? '(' + reports.length + ')' : '';
      }
      if (!reports.length) {
        container.append(el('p', { class: 'empty' }, 'Nema prijava.'));
        return;
      }
      for (const report of reports) container.append(reportRow(report));
    } catch (error) {
      container.textContent = '';
      showError(error);
    }
  }

  function reportRow(report) {
    const created = new Date(report.createdAt);
    const row = el('div', { class: 'row' },
      el('div', {},
        el('div', { class: 'name' }, report.productName),
        el('div', { class: 'meta' }, [REASONS[report.reason] || report.reason, report.retailerName, created.toLocaleString('sr-RS')].filter(Boolean).join(' · ')),
        report.listingName ? el('div', { class: 'meta' }, report.listingName) : null,
        report.note ? el('div', { class: 'meta note' }, '„' + report.note + '“') : null));
    if (report.status === 'NEW') {
      const decide = status => button => act(button,
        () => api('/reports/' + report.id, { method: 'POST', body: JSON.stringify({ status }) }),
        () => { row.remove(); toast(status === 'CONFIRMED' ? 'Označeno kao tačno.' : 'Označeno kao netačno.'); });
      const right = el('button', { class: 'primary' }, 'Tačno');
      const wrong = el('button', { class: 'secondary' }, 'Nije tačno');
      right.addEventListener('click', () => decide('CONFIRMED')(right));
      wrong.addEventListener('click', () => decide('REJECTED')(wrong));
      row.append(el('div', { class: 'actions' }, wrong, right));
    }
    return row;
  }

  // Vrste proizvoda
  async function loadCandidates() {
    const container = document.getElementById('candidates');
    container.textContent = 'Učitavam…';
    try {
      const candidates = await api('/product-types?limit=200&typeCode=' + state.type);
      showError(null);
      container.textContent = '';
      document.getElementById('count-candidates').textContent = '(' + candidates.length + (candidates.length === 200 ? '+' : '') + ')';
      if (!candidates.length) {
        container.append(el('p', { class: 'empty' }, 'Ništa ne čeka odluku.'));
        return;
      }
      for (const candidate of candidates) container.append(candidateRow(candidate));
    } catch (error) {
      container.textContent = '';
      showError(error);
    }
  }

  function candidateRow(candidate) {
    const row = el('div', { class: 'row' },
      el('div', {},
        el('div', { class: 'name' }, candidate.productName),
        el('div', { class: 'meta' }, [candidate.retailerCode, candidate.sourceCategoryName].filter(Boolean).join(' · '))));
    const decide = action => button => act(button,
      () => api('/product-types/' + candidate.retailerProductId, { method: 'POST', body: JSON.stringify({ action, productTypeCode: state.type }) }),
      () => { row.remove(); toast(action === 'ACCEPT' ? 'Dodato.' : 'Neće se više predlagati.'); if (action === 'ACCEPT') loadAssigned(); });
    const yes = el('button', { class: 'primary' }, 'Jeste ' + state.typeLabel);
    const no = el('button', { class: 'secondary' }, 'Nije');
    yes.addEventListener('click', () => decide('ACCEPT')(yes));
    no.addEventListener('click', () => decide('REJECT')(no));
    row.append(el('div', { class: 'actions' }, no, yes));
    return row;
  }

  async function loadAssigned() {
    const container = document.getElementById('assigned');
    const query = document.getElementById('assigned-search').value.trim();
    container.textContent = 'Učitavam…';
    try {
      const products = await api('/product-types/assigned?limit=200&typeCode=' + state.type + (query ? '&query=' + encodeURIComponent(query) : ''));
      showError(null);
      container.textContent = '';
      document.getElementById('count-assigned').textContent = '(' + products.length + (products.length === 200 ? '+' : '') + ')';
      if (!products.length) {
        container.append(el('p', { class: 'empty' }, query ? 'Ništa ne odgovara pretrazi.' : 'Još nema označenih proizvoda.'));
        return;
      }
      for (const product of products) container.append(assignedRow(product));
    } catch (error) {
      container.textContent = '';
      showError(error);
    }
  }

  function assignedRow(product) {
    const row = el('div', { class: 'row' },
      el('div', {},
        el('div', { class: 'name' }, product.productName),
        el('div', { class: 'meta' }, [product.retailerName, product.categoryName].filter(Boolean).join(' · '), ' ',
          el('span', { class: 'pill' }, product.reviewed ? 'ručno' : 'automatski'))));
    const remove = el('button', { class: 'secondary' }, 'Nije ' + state.typeLabel);
    remove.addEventListener('click', () => act(remove,
      () => api('/product-types/' + product.retailerProductId + '/rejection', { method: 'POST', body: JSON.stringify({ productTypeCode: state.type }) }),
      result => { row.remove(); toast(result.message); }));
    row.append(el('div', { class: 'actions' }, remove));
    return row;
  }

  // Navigation
  // Padovi aplikacije: isti pad u istoj verziji jednom, pa ceo zapis na klik.
  async function loadCrashes() {
    const container = document.getElementById('crashes');
    container.textContent = 'Učitavam…';
    try {
      const crashes = await api('/crashes');
      showError(null);
      container.textContent = '';
      document.getElementById('count-crashes').textContent = crashes.length ? '(' + crashes.length + ')' : '';
      if (!crashes.length) {
        container.append(el('p', { class: 'empty' }, 'Nema padova.'));
        return;
      }
      for (const crash of crashes) {
        const trace = el('pre', { class: 'meta', hidden: '' }, crash.latest);
        const row = el('div', { class: 'row' },
          el('div', {},
            el('div', { class: 'name' }, crash.problem),
            el('div', { class: 'meta' }, ['verzija ' + crash.appVersion, crash.times + '×', 'poslednji ' + new Date(crash.lastSeen).toLocaleString('sr-RS'), crash.phone].join(' · ')),
            trace));
        row.addEventListener('click', () => { trace.hidden = !trace.hidden; });
        container.append(row);
      }
    } catch (error) {
      container.textContent = '';
      showError(error);
    }
  }

  const loaders = { duplicates: loadDuplicates, reports: loadReports, types: () => { loadCandidates(); loadAssigned(); }, crashes: loadCrashes };
  const loaded = new Set();

  function openTab(name) {
    document.querySelectorAll('nav button').forEach(button => {
      const selected = button.dataset.tab === name;
      button.setAttribute('aria-selected', String(selected));
      document.getElementById('tab-' + button.dataset.tab).hidden = !selected;
    });
    if (!loaded.has(name)) { loaded.add(name); loaders[name](); }
  }

  document.querySelectorAll('nav button').forEach(button => button.addEventListener('click', () => openTab(button.dataset.tab)));
  document.getElementById('duplicates-more').addEventListener('click', loadDuplicates);
  document.getElementById('duplicates-all').addEventListener('change', () => {
    const boxes = document.querySelectorAll('#duplicates .card input[type="checkbox"]');
    const selectAll = selectedPairs.size < boxes.length;
    boxes.forEach(checkbox => {
      if (checkbox.checked !== selectAll) {
        checkbox.checked = selectAll;
        checkbox.dispatchEvent(new Event('change'));
      }
    });
    updateSelection();
  });
  document.getElementById('duplicates-same').addEventListener('click', event => decideSelected(true, event.target));
  document.getElementById('duplicates-different').addEventListener('click', event => decideSelected(false, event.target));
  document.querySelectorAll('#report-status .chip').forEach(chip => chip.addEventListener('click', () => {
    document.querySelectorAll('#report-status .chip').forEach(other => other.setAttribute('aria-pressed', String(other === chip)));
    state.reportStatus = chip.dataset.status;
    loadReports();
  }));
  document.querySelectorAll('#type-choice .chip').forEach(chip => chip.addEventListener('click', () => {
    document.querySelectorAll('#type-choice .chip').forEach(other => other.setAttribute('aria-pressed', String(other === chip)));
    state.type = chip.dataset.type;
    state.typeLabel = chip.dataset.label;
    loadCandidates();
    loadAssigned();
  }));
  let searchTimer;
  document.getElementById('assigned-search').addEventListener('input', () => {
    clearTimeout(searchTimer);
    searchTimer = setTimeout(loadAssigned, 300);
  });
  document.getElementById('key-save').addEventListener('click', () => {
    try { sessionStorage.setItem(KEY_STORAGE, document.getElementById('key-input').value); } catch (e) { /* session only */ }
    document.getElementById('key-banner').hidden = true;
    loaded.clear();
    openTab(document.querySelector('nav button[aria-selected="true"]').dataset.tab);
  });

  openTab('duplicates');
  api('/reports?limit=200&status=NEW')
    .then(reports => { document.getElementById('count-reports').textContent = reports.length ? '(' + reports.length + ')' : ''; })
    .catch(() => {});
})();
