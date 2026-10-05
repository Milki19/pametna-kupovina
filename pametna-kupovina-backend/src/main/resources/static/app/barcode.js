'use strict';
// Crtični kod za karticu na kasi, kao SVG. Isto kao u Android aplikaciji:
// EAN-13 kad broj to jeste, inače CODE-128, koji prima bilo koji broj i koji
// svaki trgovački čitač razume. QR se crta; ostali 2D oblici ne.

const CODE128 = ('212222 222122 222221 121223 121322 131222 122213 122312 132212 221213 ' +
  '221312 231212 112232 122132 122231 113222 123122 123221 223211 221132 ' +
  '221231 213212 223112 312131 311222 321122 321221 312212 322112 322211 ' +
  '212123 212321 232121 111323 131123 131321 112313 132113 132311 211313 ' +
  '231113 231311 112133 112331 132131 113123 113321 133121 313121 211331 ' +
  '231131 213113 213311 213131 311123 311321 331121 312113 312311 332111 ' +
  '314111 221411 431111 111224 111422 121124 121421 141122 141221 112214 ' +
  '112412 122114 122411 142112 142211 241211 221114 413111 241112 134111 ' +
  '111242 121142 121241 114212 124112 124211 411212 421112 421211 212141 ' +
  '214121 412121 111143 111341 131141 114113 114311 411113 411311 113141 ' +
  '114131 311141 411131 211412 211214 211232 2331112').split(' ');

/** Širine crta i razmaka naizmenično, počinje crtom. */
function code128Widths(value) {
  if (!/^[\x20-\x7e]+$/.test(value)) return null;
  // Samo cifre, paran broj: skup C (dve cifre po znaku, kraći kod).
  const digits = /^\d+$/.test(value) && value.length % 2 === 0;
  const codes = digits
    ? [105, ...value.match(/\d\d/g).map(Number)]
    : [104, ...[...value].map(c => c.charCodeAt(0) - 32)];
  const check = codes.reduce((sum, code, i) => sum + code * Math.max(i, 1), 0) % 103;
  return [...codes, check, 106].map(c => CODE128[c]).join('').split('').map(Number);
}

const EAN_L = ['0001101', '0011001', '0010011', '0111101', '0100011', '0110001', '0101111', '0111011', '0110111', '0001011'];
const EAN_G = ['0100111', '0110011', '0011011', '0100001', '0011101', '0111001', '0000101', '0010001', '0001001', '0010111'];
const EAN_R = ['1110010', '1100110', '1101100', '1000010', '1011100', '1001110', '1010000', '1000100', '1001000', '1110100'];
const EAN_PARITY = ['LLLLLL', 'LLGLGG', 'LLGGLG', 'LLGGGL', 'LGLLGG', 'LGGLLG', 'LGGGLL', 'LGLGLG', 'LGLGGL', 'LGGLGL'];

function ean13Valid(value) {
  if (!/^\d{13}$/.test(value)) return false;
  const d = [...value].map(Number);
  const sum = d.slice(0, 12).reduce((s, x, i) => s + x * (i % 2 ? 3 : 1), 0);
  return (10 - sum % 10) % 10 === d[12];
}

/** Moduli EAN-13 kao niz 0/1. */
function ean13Modules(value) {
  const d = [...value].map(Number);
  const parity = EAN_PARITY[d[0]];
  let bits = '101';
  for (let i = 1; i <= 6; i++) bits += (parity[i - 1] === 'L' ? EAN_L : EAN_G)[d[i]];
  bits += '01010';
  for (let i = 7; i <= 12; i++) bits += EAN_R[d[i]];
  return bits + '101';
}

function widthsToModules(widths) {
  return widths.map((w, i) => (i % 2 ? '0' : '1').repeat(w)).join('');
}


// ---------- QR kod (bajt režim, nivo ispravke M, verzije 1–10) ----------
// Za poziv u domaćinstvo i za kartice sa QR kodom. Prati ISO/IEC 18004.

// [ukupno kodnih reči, ispravka po bloku, [broj blokova, podataka u bloku]...]
const QR_M = [null,
  [26, 10, [1, 16]], [44, 16, [1, 28]], [70, 26, [1, 44]], [100, 18, [2, 32]],
  [134, 24, [2, 43]], [172, 16, [4, 27]], [196, 18, [4, 31]],
  [242, 22, [2, 38], [2, 39]], [292, 22, [3, 36], [2, 37]], [346, 26, [4, 43], [1, 44]]];
const QR_ALIGN = [null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34],
  [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50]];

const GF_EXP = new Array(512);
const GF_LOG = new Array(256);
(() => {
  let x = 1;
  for (let i = 0; i < 255; i++) {
    GF_EXP[i] = x; GF_LOG[x] = i;
    x <<= 1; if (x & 0x100) x ^= 0x11d;
  }
  for (let i = 255; i < 512; i++) GF_EXP[i] = GF_EXP[i - 255];
})();
const gfMul = (a, b) => (a && b ? GF_EXP[GF_LOG[a] + GF_LOG[b]] : 0);

function rsRemainder(data, degree) {
  let gen = [1];
  for (let i = 0; i < degree; i++) {
    const next = new Array(gen.length + 1).fill(0);
    for (let j = 0; j < gen.length; j++) {
      next[j] ^= gen[j];
      next[j + 1] ^= gfMul(gen[j], GF_EXP[i]);
    }
    gen = next;
  }
  const rem = new Array(degree).fill(0);
  for (const byte of data) {
    const factor = byte ^ rem.shift();
    rem.push(0);
    for (let j = 0; j < degree; j++) rem[j] ^= gfMul(gen[j + 1], factor);
  }
  return rem;
}

function bch(value, poly, bits) {
  let v = value << bits;
  const top = Math.floor(Math.log2(poly));
  while (v && Math.floor(Math.log2(v)) >= top) v ^= poly << (Math.floor(Math.log2(v)) - top);
  return (value << bits) | v;
}

/** Matrica QR koda kao niz redova (true = tamno), ili null kad je tekst predug. */
function qrMatrix(text) {
  const bytes = [...new TextEncoder().encode(text)];
  let version = 1;
  const dataCapacity = v => QR_M[v].slice(2).reduce((sum, [n, k]) => sum + n * k, 0);
  while (version <= 10 && 4 + (version < 10 ? 8 : 16) + bytes.length * 8 > dataCapacity(version) * 8) version++;
  if (version > 10) return null;

  // Podaci: režim bajt, dužina, bajtovi, kraj, popuna.
  const bits = [];
  const put = (value, length) => { for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1); };
  put(4, 4); put(bytes.length, version < 10 ? 8 : 16);
  bytes.forEach(b => put(b, 8));
  const capacityBits = dataCapacity(version) * 8;
  put(0, Math.min(4, capacityBits - bits.length));
  while (bits.length % 8) bits.push(0);
  const data = [];
  for (let i = 0; i < bits.length; i += 8) data.push(parseInt(bits.slice(i, i + 8).join(''), 2));
  for (let pad = 0xec; data.length < capacityBits / 8; pad ^= 0xec ^ 0x11) data.push(pad);

  // Blokovi sa ispravkom grešaka, pa preplitanje.
  const [, ecLength, ...groups] = QR_M[version];
  const blocks = [];
  let offset = 0;
  for (const [count, size] of groups) {
    for (let i = 0; i < count; i++) { blocks.push(data.slice(offset, offset + size)); offset += size; }
  }
  const ecBlocks = blocks.map(block => rsRemainder(block, ecLength));
  const codewords = [];
  const longest = Math.max(...blocks.map(b => b.length));
  for (let i = 0; i < longest; i++) blocks.forEach(b => { if (i < b.length) codewords.push(b[i]); });
  for (let i = 0; i < ecLength; i++) ecBlocks.forEach(b => codewords.push(b[i]));

  const size = version * 4 + 17;
  const grid = Array.from({ length: size }, () => new Array(size).fill(false));
  const fixed = Array.from({ length: size }, () => new Array(size).fill(false));
  const set = (r, c, dark) => { grid[r][c] = dark; fixed[r][c] = true; };

  const finder = (r0, c0) => {
    for (let r = -1; r <= 7; r++) for (let c = -1; c <= 7; c++) {
      const rr = r0 + r, cc = c0 + c;
      if (rr < 0 || cc < 0 || rr >= size || cc >= size) continue;
      const ring = Math.max(Math.abs(r - 3), Math.abs(c - 3));
      set(rr, cc, ring !== 2 && ring !== 4);
    }
  };
  finder(0, 0); finder(0, size - 7); finder(size - 7, 0);
  for (let i = 8; i < size - 8; i++) { set(6, i, i % 2 === 0); set(i, 6, i % 2 === 0); }
  const align = QR_ALIGN[version];
  const last = align[align.length - 1];
  for (const r of align) for (const c of align) {
    // Tri ugla zauzimaju nalazači; ostala idu i preko linije tajminga.
    if ((r === 6 && c === 6) || (r === 6 && c === last) || (r === last && c === 6)) continue;
    for (let dr = -2; dr <= 2; dr++) for (let dc = -2; dc <= 2; dc++) {
      set(r + dr, c + dc, Math.max(Math.abs(dr), Math.abs(dc)) !== 1);
    }
  }
  // Mesta za format i verziju se rezervišu pre podataka.
  for (let i = 0; i < 9; i++) { fixed[8][i] = fixed[i][8] = true; }
  for (let i = 0; i < 8; i++) { fixed[8][size - 1 - i] = fixed[size - 1 - i][8] = true; }
  set(size - 8, 8, true);
  if (version >= 7) for (let i = 0; i < 6; i++) for (let j = 0; j < 3; j++) { fixed[i][size - 11 + j] = fixed[size - 11 + j][i] = true; }

  // Podaci cik-cak odozdo desno, preskačući kolonu 6.
  let bit = 0;
  const allBits = [];
  codewords.forEach(b => { for (let i = 7; i >= 0; i--) allBits.push((b >>> i) & 1); });
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const c = right - j;
        const upward = ((right + 1) & 2) === 0;
        const r = upward ? size - 1 - vert : vert;
        if (!fixed[r][c]) { grid[r][c] = bit < allBits.length && allBits[bit] === 1; bit++; }
      }
    }
  }

  const masks = [
    (r, c) => (r + c) % 2 === 0, (r) => r % 2 === 0, (r, c) => c % 3 === 0, (r, c) => (r + c) % 3 === 0,
    (r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0, (r, c) => (r * c) % 2 + (r * c) % 3 === 0,
    (r, c) => ((r * c) % 2 + (r * c) % 3) % 2 === 0, (r, c) => ((r + c) % 2 + (r * c) % 3) % 2 === 0];

  const withMask = mask => {
    const m = grid.map((row, r) => row.map((dark, c) => (fixed[r][c] ? dark : dark !== masks[mask](r, c))));
    const format = bch((0 << 3) | mask, 0x537, 10) ^ 0x5412; // M = 00
    for (let i = 0; i < 15; i++) {
      const dark = ((format >>> i) & 1) === 1;
      // Prva kopija oko gornjeg levog nalazača.
      if (i < 6) m[i][8] = dark;
      else if (i === 6) m[7][8] = dark;
      else if (i === 7) m[8][8] = dark;
      else if (i === 8) m[8][7] = dark;
      else m[8][14 - i] = dark;
      // Druga kopija: gore desno i dole levo.
      if (i < 8) m[8][size - 1 - i] = dark; else m[size - 15 + i][8] = dark;
    }
    if (version >= 7) {
      const info = bch(version, 0x1f25, 12);
      for (let i = 0; i < 18; i++) {
        const dark = ((info >>> i) & 1) === 1;
        const a = Math.floor(i / 3), b = size - 11 + (i % 3);
        m[a][b] = dark; m[b][a] = dark;
      }
    }
    return m;
  };

  const penalty = m => {
    let score = 0;
    const lines = [...m, ...m[0].map((_, c) => m.map(row => row[c]))];
    for (const line of lines) {
      let run = 1;
      for (let i = 1; i <= line.length; i++) {
        if (i < line.length && line[i] === line[i - 1]) run++;
        else { if (run >= 5) score += run - 2; run = 1; }
      }
      const s = line.map(d => (d ? 1 : 0)).join('');
      score += 40 * ((s.match(/(?=10111010000|00001011101)/g) || []).length);
    }
    for (let r = 0; r < size - 1; r++) for (let c = 0; c < size - 1; c++) {
      const d = m[r][c];
      if (d === m[r][c + 1] && d === m[r + 1][c] && d === m[r + 1][c + 1]) score += 3;
    }
    const dark = m.flat().filter(Boolean).length;
    score += Math.floor(Math.abs(dark * 20 - size * size * 10) / (size * size)) * 10;
    return score;
  };

  let best = null, bestScore = Infinity;
  for (let mask = 0; mask < 8; mask++) {
    const m = withMask(mask);
    const score = penalty(m);
    if (score < bestScore) { best = m; bestScore = score; }
  }
  return best;
}

/** QR kao SVG sa tihom zonom od četiri modula. */
function qrSvg(text, label = 'QR kod') {
  const m = qrMatrix(text);
  if (!m) return null;
  const n = m.length, quiet = 4;
  let path = '';
  m.forEach((row, r) => row.forEach((dark, c) => { if (dark) path += `M${c + quiet} ${r + quiet}h1v1h-1z`; }));
  return `<svg class="qr" viewBox="0 0 ${n + quiet * 2} ${n + quiet * 2}" shape-rendering="crispEdges" role="img" aria-label="${label.replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`)}"><rect width="100%" height="100%" fill="#fff"/><path d="${path}" fill="#000"/></svg>`;
}

/** SVG za vrednost i oblik sa kartice, ili null kad oblik ne umemo. */
function barcodeSvg(value, format) {
  let modules;
  if (format === 'EAN_13' && ean13Valid(value)) {
    modules = ean13Modules(value);
  } else if (format === 'QR_CODE') {
    return qrSvg(value, 'QR kod: ' + value);
  } else if (['PDF_417', 'DATA_MATRIX', 'AZTEC'].includes(format)) {
    return null;
  } else {
    const widths = code128Widths(value);
    if (!widths) return null;
    modules = widthsToModules(widths);
  }
  const quiet = 10;
  let path = '';
  for (let i = 0; i < modules.length;) {
    if (modules[i] === '1') {
      let j = i;
      while (modules[j] === '1') j++;
      path += `M${i + quiet} 0h${j - i}v1h-${j - i}z`;
      i = j;
    } else {
      i++;
    }
  }
  return `<svg viewBox="0 0 ${modules.length + quiet * 2} 1" preserveAspectRatio="none" role="img" aria-label="Crtični kod: ${value.replace(/[&<>"']/g, c => `&#${c.charCodeAt(0)};`)}"><path d="${path}"/></svg>`;
}

if (typeof module !== 'undefined') module.exports = { qrMatrix, qrSvg, code128Widths, ean13Modules, ean13Valid, barcodeSvg, widthsToModules };
