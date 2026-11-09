// Stanje koje čita i prvi ekran (Spisak), a puni ga ekran koji se učitava tek
// kad zatreba. Ovde je, da Spisak ne bi sa sobom povukao nalog i kupovinu.

/** Stanje naloga i telefoni; puni ga account.js (Više), a Spisak samo čita da li je prijavljen. */
const account = { state: null, devices: [], error: null };

/** Kupovina po planu: stavke koje imaju prodavnicu i koliko ih je štiklirano. */
const purchaseItems = purchase => purchase.scenario.items.filter(i => i.storeId != null);
const purchaseBought = purchase => purchaseItems(purchase).filter(i => purchase.checked[i.itemId]).length;

export { account, purchaseBought, purchaseItems };
