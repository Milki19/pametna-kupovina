'use strict';
// Crtični kod za karticu na kasi, kao SVG. Isto kao u Android aplikaciji:
// EAN-13 kad broj to jeste, inače CODE-128, koji prima bilo koji broj i koji
// svaki trgovački čitač razume. QR i ostali 2D oblici se ne crtaju.

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

/** SVG za vrednost i oblik sa kartice, ili null kad oblik ne umemo. */
function barcodeSvg(value, format) {
  let modules;
  if (format === 'EAN_13' && ean13Valid(value)) {
    modules = ean13Modules(value);
  } else if (['QR_CODE', 'PDF_417', 'DATA_MATRIX', 'AZTEC'].includes(format)) {
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

if (typeof module !== 'undefined') module.exports = { code128Widths, ean13Modules, ean13Valid, barcodeSvg, widthsToModules };
