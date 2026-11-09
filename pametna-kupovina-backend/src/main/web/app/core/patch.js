// Ciljano iscrtavanje bez biblioteke: novi HTML ekrana se poredi sa onim što
// je već na ekranu, pa se menjaju samo delovi koji su drugačiji. Na spisku od
// 40 stavki brisanje, izmena ili spajanje jedne zamene jedan red, a ne sve;
// fokus, mesto na ekranu i otkucan tekst u ostalim delovima ostaju.
//
// Delovi sa data-key (stavka spiska) se prepoznaju po ključu i kad promene
// mesto. Delovi bez ključa se uparuju redom, po istom sadržaju.

/** Poslednje stanje izmena, za proveru u pregledaču (i pkTiming). */
const patchStats = { kept: 0, made: 0 };

const keyOf = node => (node.nodeType === 1 && node.dataset.key) || null;
const blank = node => node.nodeType === 3 && !node.nodeValue.trim();

/**
 * Zameni decu elementa `parent` decom iz `html`, a isto ostavi gde jeste.
 * Čvor se zadržava kad je isti kao novi (isEqualNode: oznaka, atributi i
 * sav sadržaj); inače ide novi. Vraća koliko je zadržano i koliko napravljeno.
 */
function patchChildren(parent, html) {
  const template = document.createElement('template');
  template.innerHTML = html;
  const fresh = [...template.content.childNodes].filter(node => !blank(node));
  const old = [...parent.childNodes].filter(node => !blank(node));
  const byKey = new Map();
  const unkeyed = [];
  for (const node of old) {
    const key = keyOf(node);
    if (key && !byKey.has(key)) byKey.set(key, node); else unkeyed.push(node);
  }
  let cursor = 0;
  let kept = 0;
  const wanted = fresh.map(node => {
    const key = keyOf(node);
    let same = null;
    if (key) {
      const found = byKey.get(key);
      if (found && found.isEqualNode(node)) same = found;
    } else {
      // Redom: prvi isti čvor posle poslednjeg zadržanog.
      for (let i = cursor; i < unkeyed.length; i++) {
        if (unkeyed[i].isEqualNode(node)) { same = unkeyed[i]; cursor = i + 1; break; }
      }
    }
    if (same) { kept++; return same; }
    return node;
  });
  const keep = new Set(wanted);
  for (const node of [...parent.childNodes]) {
    if (!keep.has(node)) node.remove();
  }
  // Postavi redom; čvor koji je već na svom mestu se ne pomera.
  let place = parent.firstChild;
  for (const node of wanted) {
    if (node === place) { place = place.nextSibling; continue; }
    parent.insertBefore(node, place);
  }
  patchStats.kept = kept;
  patchStats.made = wanted.length - kept;
  return patchStats;
}

export { patchChildren, patchStats };
