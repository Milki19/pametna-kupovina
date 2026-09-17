-- "meso" on a list bought a 130 g chicken pâté and "voće" a hot pepper: MEAT
-- held salami, sausages and spreads next to fresh cuts, and fruit and
-- vegetables were one type. The owner decided (nastavak 27): "meso" is fresh
-- meat, "voće" is fruit, "povrće" is vegetables, and a list knows "jabuke",
-- "banane", "kruške", "paradajz" and the like.
--
-- MEAT now holds a cut, offal or minced meat of a named animal, never cured,
-- smoked, cooked, marinated, breaded, ready-made or frozen. Fresh produce is
-- FRUIT or VEGETABLE by its name; dried, pickled and cooked produce and nuts
-- are neither. As in V83 a product gets such a type only where its chain files
-- it under the same category, and one without a category waits on the admin
-- page. Word lists were checked against every chain's products (17.09.).

UPDATE app.product_type
SET name = 'Sveže meso',
    updated_at = NOW()
WHERE code = 'MEAT';

INSERT INTO app.product_type (code, name, product_category_id)
SELECT kind.code, kind.name, category.id
FROM (
    VALUES
        ('FRUIT', 'Sveže voće'),
        ('VEGETABLE', 'Sveže povrće')
) AS kind (code, name)
JOIN app.product_category AS category
  ON category.code = 'FRESH_PRODUCE';

UPDATE app.product_type
SET needs_source_category = TRUE,
    updated_at = NOW()
WHERE code IN ('FRUIT', 'VEGETABLE');

UPDATE app.product_type
SET active = FALSE,
    needs_source_category = FALSE,
    updated_at = NOW()
WHERE code = 'FRESH_PRODUCE';

UPDATE app.product_type_rule AS rule
SET active = FALSE,
    updated_at = NOW()
FROM app.product_type AS type
WHERE type.id = rule.product_type_id
  AND type.code = 'FRESH_PRODUCE'
  AND rule.active;

CREATE TEMP TABLE food_words (
    list TEXT PRIMARY KEY,
    pattern TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO food_words (list, pattern)
VALUES
    -- An animal, a cut, offal or minced meat.
    ('meat',
     '\m(svinj[a-z]*|junec[a-z]*|junetin[a-z]*|jun|telec[a-z]*|teletin[a-z]*|goved[a-z]*|govedin[a-z]*'
     || '|jagnje|jagnjec[a-z]*|jagnjetin[a-z]*|jarec[a-z]*|jaretin[a-z]*|ovcij[a-z]*|ovcetin[a-z]*'
     || '|pile|pilec[a-z]*|pilet[a-z]*|pilen[a-z]*|pil|curec[a-z]*|curetin[a-z]*|curk[a-z]*|pac(ij|j)[a-z]*'
     || '|guscij[a-z]*|konjsk[a-z]*|prasec[a-z]*|prase|prasetin[a-z]*|kunic[a-z]*|zec|zecij[a-z]*'
     || '|divljac[a-z]*|fazan[a-z]*|kokosk[a-z]*|kokosj[a-z]*|roster|angus|meso|mleveno|fasirano|usitnjeno'
     || '|but|buta|butkic[a-z]*|plecka|plecke|podplecka|vrat|vratina|kare|file|filet[a-z]*|fileta|filea'
     || '|rebra|rebarca|kotlet[a-z]*|krmenadl[a-z]*|slabin[a-z]*|lopatic[a-z]*|rozbratn[a-z]*|biftek'
     || '|ramstek|steak|stek|striplion|kolenic[a-z]*|buncek|jetr[a-z]*|dzigeric[a-z]*|bubreg'
     || '|bubrezi|zeludac|zeluci|zeludic[a-z]*|skembic[a-z]*|jezik|jezici|mozak|kost|kosti|koske|ledja'
     || '|krila|krilca|batak|bataci|karabatak[a-z]*|grudi|snicl[a-z]*|odrez[a-z]*|trbusin[a-z]*'
     || '|potrbusin[a-z]*|podlaktic[a-z]*|potkolenic[a-z]*|nogice|papci|polutk[a-z]*|polovin[a-z]*'
     || '|cetvrt[a-z]*|karlic[a-z]*|iznutric[a-z]*|vesalic[a-z]*|osobuc[a-z]*|ossobuc[a-z]*|oso buko'
     || '|rack|pauflek|obraz[a-z]*|rep|glav(a|e)|francusk[a-z]* obrad[a-z]*|flank|rib eye|ribeye|t bone'
     || '|tomahawk)\M'),
    -- Cured, smoked, cooked, marinated, breaded, ready-made or frozen meat,
    -- sausages, pâté and spreads, fat, and meat inside another dish. Meat for
    -- soup, for drying and for a dish is still fresh.
    ('not fresh meat',
     '\m(kob[a-z]*|salam[a-z]*|salami|sunk[a-z]*|prsut[a-z]*|slanin(a|e|u|i)?|bekon|panceta'
     || '|pancetin[a-z]*|spek|speck|virsl[a-z]*|hrenovk[a-z]*|frankfurt[a-z]*|parizer|parisk[a-z]*'
     || '|mortadel[a-z]*|safalad[a-z]*|extrawurst|krainer|kranjsk[a-z]*|krainsk[a-z]*|sudzuk|kulen[a-z]*'
     || '|cajn[a-z]*|budimsk[a-z]*|lovack[a-z]*|fuet|chorizo|jamon|serrano|iberico|gvantacale|guanciale'
     || '|budjola|budola|posebn[a-z]*|delikates[a-z]*|omot[a-z]*|crev[a-z]*|poli|pik|gala|tip top'
     || '|papr|oblikovan[a-z]*|smazalic[a-z]*|argeta|zaru|dimnjen[a-z]*|parfe|klasik|classic|specijal'
     || '|pljeka|lovcen[a-z]*'
     || '|njegus[a-z]*|kras|krask[a-z]*|kosmajsk[a-z]*|lokavsk[a-z]*|zlatiborsk[a-z]*|uzick[a-z]*'
     || '|leskovack[a-z]*|pasteta|pastet[a-z]*|paste|past|pate|namaz[a-z]*|rillette|krem|pecenic[a-z]*'
     || '|dimljen[a-z]*|dimlj[a-z]*|dim|prodimljen[a-z]*|suv[a-z]*|kuvan[a-z]*|baren[a-z]*|pecen[a-z]*'
     || '|grilovan[a-z]*|narezak|narezci|naresci|narezano|narezana|slajs|slice|konzerv[a-z]*|konz[a-z]*|kon'
     || '|limenk[a-z]*|gotov[a-z]*|obrok|sarm[a-z]*|hase|paprikas|panir[a-z]*|pohovan[a-z]*|nuggets'
     || '|nagets|fritesi|cordon|bleu|stapic[a-z]*|kroket[a-z]*|giros|kebab|raznjic[a-z]*|cevap[a-z]*'
     || '|pljeskavic[a-z]*|burger[a-z]*|cufte|cuft[a-z]*|medaljon[a-z]*|marin[a-z]*|zacinjen[a-z]*|bbq'
     || '|bafalo|buffalo|pikant[a-z]*|ready|cook|sous|carpaccio|tartar|aspik|pihtij[a-z]*|hladetin[a-z]*'
     || '|svargl[a-z]*|tlacenic[a-z]*|krvavic[a-z]*|kavurma|mast|cvarci|cvarak|loj|masno|tkivo'
     || '|masnoc[a-z]*|pica|pizza|pizzu|sendvic|tost|lazanj[a-z]*|pasta|spagete|pirinac|pasulj|grasak'
     || '|grask[a-z]*|boranij[a-z]*|prebranac|djuvec|krompir[a-z]*|povrcem|povrce|sir|sirom|parmezan'
     || '|salat[a-z]*|punjen[a-z]*|paradajz[a-z]*|paprik[a-z]*|luk|lukom|kupus|sosu|testen[a-z]*|muck[a-z]*'
     || '|pita|tortilj[a-z]*|wok|corbic[a-z]*|posn[a-z]*|vegan[a-z]*|veggie'
     || '|biljn[a-z]*|soja|sojin[a-z]*|seitan|zacin[a-z]*|cips|snack|grickalic[a-z]*|corba|bujon[a-z]*'
     || '|supa kock[a-z]*|kock[a-z]* za sup[a-z]*|smrznut[a-z]*|smrz|smrzn[a-z]*|zamrz[a-z]*|zamr'
     || '|riba|ribe|riblj[a-z]*|tuna|tune|tunj[a-z]*|losos[a-z]*|sardin[a-z]*|sardel[a-z]*|skus[a-z]*'
     || '|oslic[a-z]*|pastrmk[a-z]*|brancin[a-z]*|orad[a-z]*|saran[a-z]*|pangas[a-z]*|pangac[a-z]*'
     || '|haring[a-z]*|bakalar[a-z]*|incun[a-z]*|lignj[a-z]*|kozic[a-z]*|grdob[a-z]*)\M'
     || '|(?<!za )\m(supa|supe|supu|susen[a-z]*)\M|(?<!pripremu )\mjel(o|a)\M|(?<!za )\mgulas\M'
     -- Fresh meat is not sold in packs under 200 g; slices and spreads are.
     || '|\m([1-9][0-9]?|1[0-9][0-9]) (g|gr|grama)\M'),
    ('pet food',
     '\m(hrana|za (macke|mace|mac|pse|pasa|ljubimce)|macke|macji|maca|pse|psi|pseci|psa|sheba|whiskas|whi'
     || '|felix|friskies|dreamies|kitekat|purina|pedigree|brit|kitty|cat|dog|vitakraft|yums|moksi|cesar'
     || '|gourmet|proof|my love|wise|buddy|zvakalic[a-z]*|poslastic[a-z]*|woof)\M'),
    -- Drinks, household goods, books and toys that share a word with food:
    -- apple cider, a plum brandy, a lemon-scented gel, a shopping bag.
    ('not food',
     '\m(rakij[a-z]*|rak|liker[a-z]*|koktel[a-z]*|sajder|cider|cyder|somersby|radler|vermut|whiskey'
     || '|viski|vodk[a-z]*|pelinkovac|gorki list|alkohol[a-z]*|knjg[a-z]*|knjig[a-z]*|casopis[a-z]*|karte'
     || '|balon[a-z]*|orhidej[a-z]*|noz|ljustac[a-z]*|rucka|ruckom|tregeric[a-z]*|lubrikant[a-z]*|airwick'
     || '|zyn|veev|igrack[a-z]*|beba|gel|osvezivac[a-z]*|deterdzent[a-z]*|omeksivac[a-z]*|sveca|svece'
     || '|posuda)\M'),
    -- Fruit and vegetables are not sold by the litre; Lidl's "ml." in a meat
    -- name is minced, so meat does not use this.
    ('sold by the litre',
     '\m(l|ml|cl)\M'),
    ('fruit',
     '\m(jabuk(a|e|u|om)?|krusk(a|e|u|om)?|viljamovk(a|e)|banan(a|e|u|om)?|pomorandz(a|e|u|om)?'
     || '|narandz(a|e|u|om)|mandarin(a|e|u|om)?|klementin(a|e|u|om)?|mandora|mineola|minjola'
     || '|limun(a|i|om)?|limet(a|e|u)|limes|grejp|grejpfrut(i)?|pomelo|kivi|kivano|kiwano|ananas(i)?'
     || '|mang(o|a)|avokad(o|a)|breskv(a|e|u|om)?|nektarin(a|e|u|om)?|kajsij(a|e|u|om)?|sljiv(a|e|u|om)?'
     || '|tresnj(a|e|u|om)?|visnj(a|e|u|om)?|jagod(a|e|u|om)?|malin(a|e|u|om)?|kupin(a|e|u|om)?'
     || '|borovnic(a|e|u|om)?|ribizl(a|e|u|om)?|brusnic(a|e|u|om)?|aronij(a|e|u)?|bobicast[a-z]*|bobice'
     || '|grozdj(e|a|u|em)|grozde|lubenic(a|e|u|om)?|wassermelon[a-z]*|dinj(a|e|u|om)?|kantalup(e|a)?'
     || '|smokv(a|e|u|om)?|figs|dunj(a|e|u|om)?|musmul(a|e|u)?|kaki|fisalis|fizalis|physalis'
     || '|marakuj(a|e|u)|maracuja|passion|papaj(a|e|u)|papaya|pitaj(a|e|u)|pitahaya|karambol(a|e|u)'
     || '|lici|lichi|rambutan|mangostin|mangistan|kumkvat|kumquat|granadil(a|e)|guava|chirimoya'
     || '|tamarilo|tamarind|budahfinger|kokos|kokosov|urm(a|e)|vocn(a|e|i) salat[a-z]*'
     || '|vocn(a|i) (mix|miks))\M'),
    -- Fruit only when no vegetable is named: "Bar Nar" also sells ginger.
    ('fruit when alone',
     '\m(nar|nara|voce|voca)\M'),
    ('vegetable',
     '\m(paradajz(a|i|om)?|paradaiz|parad|cherry|ceri|sljivar|kumato|krastav(ac|ca|ci|ce|cic[a-z]*)'
     || '|kornison(i|a)?|paprik(a|e|u|om)|pap|papricic[a-z]*|babur(a|e|u)|feferon[a-z]*|cili|chili'
     || '|halapenj[a-z]*|jalapeno|habanero|jolokia|krompir[a-z]*|krompi|batat|luk(a|om)?|praziluk[a-z]*'
     || '|vlasac|salot|shalot|shallots|ljutik[a-z]*|srebrenjak|sremus|sargarep(a|e|u|om)?|mrkv(a|e|u|om)?'
     || '|persun[a-z]*|celer[a-z]*|pastrn(ak|aka|jak)|paskanat|cvekl(a|e|u|om)?|rotkv(a|e|u)'
     || '|rotkvic(a|e|u)|daikon|ren|repa|keleraba|korabica|kupus(a|om)?|kelj|prokelj|karfiol[a-z]*'
     || '|brokol[a-z]*|romanesk[a-z]*|romanesco|choi|paksoi|tikvic(a|e|u|om)?|tikv(a|e|u)'
     || '|bundev(a|e|u|om)?|dulek|hokaido|butternut|patlidzan[a-z]*|boranij(a|e|u)|grasak|graska'
     || '|mahun[a-z]*|kukuruz(a)?|spanac(a)?|spinac[a-z]*|blitv(a|e|u)|zelj(e|a)|kopriv(a|e|u)'
     || '|lobod(a|e|u)|rastan|iceberg|ajsberg|rukol(a|e|u)|rukolic(a|e)|rucola|radic|radicchio'
     || '|cikorij(a|e)|endivij(a|e)|matovilac|motovilac|valerian(a|e)|lollo|lolo|romana|mixticanza'
     || '|puterica|kristalk(a|e)|mirodjij(a|e|u)|bosiljak|nana|menta|ruzmarin|timijan|majcina dusica'
     || '|majoran|maticnjak|zalfij(a|e)|origano|korijander|estragon|djumbir|dumbir|kurkuma'
     || '|pecurk(a|e|u|om)?|sampinjon[a-z]*|bukovac(a|e|u)|shiitake|siitake|sitake|shii take'
     || '|king oyster|lavlja griva|portobel[a-z]*|vrganj[a-z]*|lisicark[a-z]*|enoki|tartuf[a-z]*'
     || '|spargl(a|e|u)|sparglji|artick?ok[a-z]*|komorac|finokio|okra|bamij(a|e|u)|cicok(a|e)'
     || '|rabarbar(a|e)|yuca|klic(e|a)|mikrobilje|volovsko srce|bivolje srce|hrastov list)\M'),
    -- A salad, herbs or greens only when no fruit is named: "voćna salata".
    ('vegetable when alone',
     '\m(salat(a|e|u|om)?|bilje|povrc(e|a|em)|zelen)\M'),
    -- Dried, candied, pickled, cooked or pasteurised produce, juice, jam,
    -- nuts, olives, pulses and ready dishes. Oranges for juice and
    -- vegetables for soup are still fresh.
    ('not fresh produce',
     '\m(suv[a-z]*|susen[a-z]*|osusen[a-z]*|osmotski|kandiran[a-z]*|liofiliz[a-z]*|dehidrir[a-z]*'
     || '|kisel[a-z]*|tursij[a-z]*|marinir[a-z]*|pecen(a|i|o|e)|kuvan(a|i|o|e)|dinstan(a|i|o|e)'
     || '|przen(a|i|o|e)|baren(a|i|o|e)|pasteriz[a-z]*|smoothie|napitak|drink|limunad[a-z]*|flips|dzem'
     || '|kompot|sirup|pekmez|marmelad[a-z]*|zimnic[a-z]*|ajvar|pinjur[a-z]*|sos|pesto|pasta|namaz'
     || '|konzerv[a-z]*|zamrznut[a-z]*|smrznut[a-z]*|corba|krem|ulj[a-z]*|brasn[a-z]*|cokoladiran[a-z]*'
     || '|lesnik[a-z]*|badem[a-z]*|kikiriki|kikirki|indijski|pistac[a-z]*|kesten[a-z]*|jezgr(o|a)'
     || '|maslin(a|e)|olives|pasulj|socivo|leblebij[a-z]*|slanutak|gotovo jelo|mesan(a|e) salat[a-z]*'
     || '|salat[a-z]* mesan[a-z]*|biser salat[a-z]*|pita|krofn[a-z]*|kas(a|e)|hleb[a-z]*|punjen[a-z]*'
     || '|casopis|knjig[a-z]*|keks[a-z]*|vrecic[a-z]*|nutella|ovsen[a-z]*|musli|pekar[a-z]*|kolac[a-z]*'
     || '|tort[a-z]*|strudl[a-z]*|sladoled[a-z]*|jogurt[a-z]*|mlek(o|a)|bombon[a-z]*|zvak[a-z]*'
     || '|italijansk[a-z]* salat[a-z]*|grck[a-z]* salat[a-z]*|rusk[a-z]* salat[a-z]*|sampon[a-z]*'
     || '|sapun[a-z]*|parfem[a-z]*|kecap|koncentrovan[a-z]*|teglic[a-z]*|tub(a|i)|urnebes|obrok'
     || '|lekovit[a-z]*|francusk[a-z]* salat[a-z]*|fil|filet[a-z]*|gingerbread|darkom|bom|energ[a-z]*'
     || '|fanta|mes(o|om|a)|mesn[a-z]*)\M'
     || '|(?<!za )\m(sok|djus|supa|supe|supu|corbu|corbe|cips|chips|pomfrit|pire)\M');

UPDATE app.product_type_rule AS rule
SET include_pattern = meat.pattern,
    exclude_pattern = not_fresh.pattern || '|' || pets.pattern || '|' || not_food.pattern,
    confidence = 0.8900,
    updated_at = NOW()
FROM app.product_type AS type,
     food_words AS meat,
     food_words AS not_fresh,
     food_words AS pets,
     food_words AS not_food
WHERE type.id = rule.product_type_id
  AND type.code = 'MEAT'
  AND rule.active
  AND rule.retailer_id IS NULL
  AND meat.list = 'meat'
  AND not_fresh.list = 'not fresh meat'
  AND pets.list = 'pet food'
  AND not_food.list = 'not food';

INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT type.id,
       include_words.pattern,
       STRING_AGG(exclude_words.pattern, '|' ORDER BY exclude_words.list),
       40,
       0.8800
FROM (
    VALUES
        ('FRUIT', 'fruit', 'not food'),
        ('FRUIT', 'fruit', 'sold by the litre'),
        ('FRUIT', 'fruit', 'not fresh produce'),
        ('FRUIT', 'fruit', 'pet food'),
        ('FRUIT', 'fruit', 'vegetable'),
        ('FRUIT', 'fruit when alone', 'not food'),
        ('FRUIT', 'fruit when alone', 'sold by the litre'),
        ('FRUIT', 'fruit when alone', 'not fresh produce'),
        ('FRUIT', 'fruit when alone', 'pet food'),
        ('FRUIT', 'fruit when alone', 'vegetable'),
        ('FRUIT', 'fruit when alone', 'vegetable when alone'),
        ('VEGETABLE', 'vegetable', 'not food'),
        ('VEGETABLE', 'vegetable', 'sold by the litre'),
        ('VEGETABLE', 'vegetable', 'not fresh produce'),
        ('VEGETABLE', 'vegetable', 'pet food'),
        ('VEGETABLE', 'vegetable', 'fruit'),
        ('VEGETABLE', 'vegetable when alone', 'not food'),
        ('VEGETABLE', 'vegetable when alone', 'sold by the litre'),
        ('VEGETABLE', 'vegetable when alone', 'not fresh produce'),
        ('VEGETABLE', 'vegetable when alone', 'pet food'),
        ('VEGETABLE', 'vegetable when alone', 'fruit'),
        ('VEGETABLE', 'vegetable when alone', 'fruit when alone')
) AS rule (type_code, include_list, exclude_list)
JOIN app.product_type AS type
  ON type.code = rule.type_code
JOIN food_words AS include_words
  ON include_words.list = rule.include_list
JOIN food_words AS exclude_words
  ON exclude_words.list = rule.exclude_list
GROUP BY type.id, include_words.pattern;

-- The rule set changed: predictions and suggestions carry a new version.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v11',
        'taxonomy-v12'
    );
END $$;

-- Words for the new kinds. "meso" leaves out marinated, frozen and cooked
-- chicken and neck, which the narrower grill types may still hold, and bones,
-- trotters and offal: the cheapest "meso" was pig's trotters.
INSERT INTO app.shopping_intent (code, name)
SELECT code, name
FROM app.product_type
WHERE code IN ('FRUIT', 'VEGETABLE');

UPDATE app.shopping_intent
SET name = 'Sveže meso',
    updated_at = NOW()
WHERE code = 'MEAT';

INSERT INTO app.shopping_intent_product_type (
    shopping_intent_id,
    product_type_id,
    substitution_level,
    match_priority,
    enabled_by_default
)
SELECT intent.id, type.id, 'EXACT', 10, TRUE
FROM (
    VALUES
        ('FRUIT', 'FRUIT'),
        ('VEGETABLE', 'VEGETABLE'),
        ('MEAT', 'CHICKEN_FILLET'),
        ('MEAT', 'CHICKEN_DRUMSTICK'),
        ('MEAT', 'CHICKEN_WINGS'),
        ('MEAT', 'PORK_NECK_FRESH')
) AS allowed (intent_code, type_code)
JOIN app.shopping_intent AS intent
  ON intent.code = allowed.intent_code
JOIN app.product_type AS type
  ON type.code = allowed.type_code
ON CONFLICT (shopping_intent_id, product_type_id) DO NOTHING;

UPDATE app.shopping_intent_alias AS alias
SET shopping_intent_id = intent.id
FROM app.shopping_intent AS intent
WHERE (alias.normalized_alias, intent.code) IN (('voce', 'FRUIT'), ('povrce', 'VEGETABLE'));

INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern
)
SELECT intent.id, word.alias, 5, word.pattern
FROM (
    VALUES
        ('MEAT', 'sveze meso', NULL),
        ('FRUIT', 'sveze voce', NULL),
        ('FRUIT', 'jabuka', '^(?!.*\m(kaki|japansk[a-z]*)\M).*\mjabuk(a|e|u|om)?\M'),
        ('FRUIT', 'jabuke', '^(?!.*\m(kaki|japansk[a-z]*)\M).*\mjabuk(a|e|u|om)?\M'),
        ('FRUIT', 'jabuku', '^(?!.*\m(kaki|japansk[a-z]*)\M).*\mjabuk(a|e|u|om)?\M'),
        ('FRUIT', 'kruska', '\mkrusk(a|e|u|om)?\M'),
        ('FRUIT', 'kruske', '\mkrusk(a|e|u|om)?\M'),
        ('FRUIT', 'krusku', '\mkrusk(a|e|u|om)?\M'),
        ('FRUIT', 'banana', '\mbanan(a|e|u|om)?\M'),
        ('FRUIT', 'banane', '\mbanan(a|e|u|om)?\M'),
        ('FRUIT', 'bananu', '\mbanan(a|e|u|om)?\M'),
        ('FRUIT', 'pomorandza', '\m(pomorandz[a-z]*|narandz(a|e|u|om))\M'),
        ('FRUIT', 'pomorandze', '\m(pomorandz[a-z]*|narandz(a|e|u|om))\M'),
        ('FRUIT', 'narandza', '\m(pomorandz[a-z]*|narandz(a|e|u|om))\M'),
        ('FRUIT', 'narandze', '\m(pomorandz[a-z]*|narandz(a|e|u|om))\M'),
        ('FRUIT', 'mandarina', '\m(mandarin|klementin)(a|e|u|om)?\M'),
        ('FRUIT', 'mandarine', '\m(mandarin|klementin)(a|e|u|om)?\M'),
        ('FRUIT', 'klementine', '\mklementin(a|e|u|om)?\M'),
        ('FRUIT', 'limun', '\mlimun(a|i|om)?\M'),
        ('FRUIT', 'limuni', '\mlimun(a|i|om)?\M'),
        ('FRUIT', 'limeta', '\m(limet(a|e|u)|limes)\M'),
        ('FRUIT', 'limete', '\m(limet(a|e|u)|limes)\M'),
        ('FRUIT', 'grejpfrut', '\mgrejp(frut[a-z]*)?\M'),
        ('FRUIT', 'kivi', '\mkivi\M'),
        ('FRUIT', 'ananas', '\mananas[a-z]*\M'),
        ('FRUIT', 'mango', '\mmang(o|a)\M'),
        ('FRUIT', 'avokado', '\mavokad(o|a)\M'),
        ('FRUIT', 'breskva', '\mbreskv(a|e|u|om)?\M'),
        ('FRUIT', 'breskve', '\mbreskv(a|e|u|om)?\M'),
        ('FRUIT', 'nektarina', '\mnektarin(a|e|u|om)?\M'),
        ('FRUIT', 'nektarine', '\mnektarin(a|e|u|om)?\M'),
        ('FRUIT', 'kajsija', '\mkajsij(a|e|u|om)?\M'),
        ('FRUIT', 'kajsije', '\mkajsij(a|e|u|om)?\M'),
        ('FRUIT', 'sljiva', '\msljiv(a|e|u|om)?\M'),
        ('FRUIT', 'sljive', '\msljiv(a|e|u|om)?\M'),
        ('FRUIT', 'tresnja', '\mtresnj(a|e|u|om)?\M'),
        ('FRUIT', 'tresnje', '\mtresnj(a|e|u|om)?\M'),
        ('FRUIT', 'visnja', '\mvisnj(a|e|u|om)?\M'),
        ('FRUIT', 'visnje', '\mvisnj(a|e|u|om)?\M'),
        ('FRUIT', 'jagoda', '\mjagod(a|e|u|om)?\M'),
        ('FRUIT', 'jagode', '\mjagod(a|e|u|om)?\M'),
        ('FRUIT', 'malina', '\mmalin(a|e|u|om)?\M'),
        ('FRUIT', 'maline', '\mmalin(a|e|u|om)?\M'),
        ('FRUIT', 'kupina', '\mkupin(a|e|u|om)?\M'),
        ('FRUIT', 'kupine', '\mkupin(a|e|u|om)?\M'),
        ('FRUIT', 'borovnica', '\mborovnic(a|e|u|om)?\M'),
        ('FRUIT', 'borovnice', '\mborovnic(a|e|u|om)?\M'),
        ('FRUIT', 'ribizla', '\mribizl(a|e|u|om)?\M'),
        ('FRUIT', 'grozdje', '\m(grozdj(e|a|u|em)|grozde)\M'),
        ('FRUIT', 'lubenica', '\m(lubenic(a|e|u|om)?|wassermelon[a-z]*)\M'),
        ('FRUIT', 'lubenicu', '\m(lubenic(a|e|u|om)?|wassermelon[a-z]*)\M'),
        ('FRUIT', 'dinja', '\mdinj(a|e|u|om)?\M'),
        ('FRUIT', 'dinju', '\mdinj(a|e|u|om)?\M'),
        ('FRUIT', 'smokve', '\msmokv(a|e|u|om)?\M'),
        ('FRUIT', 'nar', '\mnar(a)?\M'),
        ('FRUIT', 'dunje', '\mdunj(a|e|u|om)?\M'),
        ('VEGETABLE', 'sveze povrce', NULL),
        ('VEGETABLE', 'paradajz', '^(?!.*\m(cherry|ceri|cheri|sljivar|dattarino|daterino|kumato)\M).*\m(paradajz[a-z]*|paradaiz|parad)\M'),
        ('VEGETABLE', 'cherry paradajz', '\m(cherry|ceri|cheri|sljivar|dattarino|daterino)\M'),
        ('VEGETABLE', 'ceri paradajz', '\m(cherry|ceri|cheri|sljivar|dattarino|daterino)\M'),
        ('VEGETABLE', 'krastavac', '\mkrastav(ac|ca|ci|ce|cic[a-z]*)\M'),
        ('VEGETABLE', 'krastavci', '\mkrastav(ac|ca|ci|ce|cic[a-z]*)\M'),
        ('VEGETABLE', 'krastavce', '\mkrastav(ac|ca|ci|ce|cic[a-z]*)\M'),
        ('VEGETABLE', 'paprika', '\m(paprik(a|e|u|om)|pap|papricic[a-z]*|babur(a|e|u)|silj(a|e))\M'),
        ('VEGETABLE', 'paprike', '\m(paprik(a|e|u|om)|pap|papricic[a-z]*|babur(a|e|u)|silj(a|e))\M'),
        ('VEGETABLE', 'papriku', '\m(paprik(a|e|u|om)|pap|papricic[a-z]*|babur(a|e|u)|silj(a|e))\M'),
        ('VEGETABLE', 'babura', '\mbabur(a|e|u)\M'),
        ('VEGETABLE', 'babure', '\mbabur(a|e|u)\M'),
        ('VEGETABLE', 'ljuta paprika', '^(?=.*\m(paprik[a-z]*|pap|papricic[a-z]*|feferon[a-z]*|cili|chili|habanero|halapenj[a-z]*|jalapeno)\M).*\m(ljut[a-z]*|feferon[a-z]*|cili|chili|habanero|halapenj[a-z]*|jalapeno)\M'),
        ('VEGETABLE', 'ljute paprike', '^(?=.*\m(paprik[a-z]*|pap|papricic[a-z]*|feferon[a-z]*|cili|chili|habanero|halapenj[a-z]*|jalapeno)\M).*\m(ljut[a-z]*|feferon[a-z]*|cili|chili|habanero|halapenj[a-z]*|jalapeno)\M'),
        ('VEGETABLE', 'krompir', '^(?!.*\m(slatk[a-z]*|batat)\M).*\m(krompir[a-z]*|krompi)\M'),
        ('VEGETABLE', 'mladi krompir', '^(?=.*\mmlad[a-z]*\M).*\m(krompir[a-z]*|krompi)\M'),
        ('VEGETABLE', 'slatki krompir', '\m(batat|slatk[a-z]* krompir[a-z]*|krompir[a-z]* slatk[a-z]*)\M'),
        ('VEGETABLE', 'batat', '\m(batat|slatk[a-z]* krompir[a-z]*|krompir[a-z]* slatk[a-z]*)\M'),
        ('VEGETABLE', 'luk', '^(?!.*\m(beli|bijeli|mlad[a-z]*|praziluk[a-z]*|vlasac|sremus|shalot|shallots|salot)\M).*\mluk\M'),
        ('VEGETABLE', 'crni luk', '^(?!.*\mmlad[a-z]*\M).*\m(crni luk|luk crni)\M'),
        ('VEGETABLE', 'luk crni', '^(?!.*\mmlad[a-z]*\M).*\m(crni luk|luk crni)\M'),
        ('VEGETABLE', 'crveni luk', '^(?!.*\mmlad[a-z]*\M).*\m(crveni luk|luk crveni|ljubicasti luk|luk ljubicasti)\M'),
        ('VEGETABLE', 'luk crveni', '^(?!.*\mmlad[a-z]*\M).*\m(crveni luk|luk crveni|ljubicasti luk|luk ljubicasti)\M'),
        ('VEGETABLE', 'beli luk', '\m(beli luk|luk beli|bijeli luk|luk bijeli)\M'),
        ('VEGETABLE', 'luk beli', '\m(beli luk|luk beli|bijeli luk|luk bijeli)\M'),
        ('VEGETABLE', 'mladi luk', '^(?=.*\mmlad[a-z]*\M).*\mluk\M'),
        ('VEGETABLE', 'praziluk', '\mpraziluk[a-z]*\M'),
        ('VEGETABLE', 'sargarepa', '\m(sargarep(a|e|u|om)?|mrkv(a|e|u|om)?)\M'),
        ('VEGETABLE', 'sargarepu', '\m(sargarep(a|e|u|om)?|mrkv(a|e|u|om)?)\M'),
        ('VEGETABLE', 'mrkva', '\m(sargarep(a|e|u|om)?|mrkv(a|e|u|om)?)\M'),
        ('VEGETABLE', 'persun', '\mpersun[a-z]*\M'),
        ('VEGETABLE', 'celer', '\mceler[a-z]*\M'),
        ('VEGETABLE', 'cvekla', '\mcvekl(a|e|u|om)?\M'),
        ('VEGETABLE', 'cveklu', '\mcvekl(a|e|u|om)?\M'),
        ('VEGETABLE', 'kupus', '\mkupus[a-z]*\M'),
        ('VEGETABLE', 'kelj', '\mkelj\M'),
        ('VEGETABLE', 'karfiol', '\mkarfiol[a-z]*\M'),
        ('VEGETABLE', 'brokoli', '\mbrokol[a-z]*\M'),
        ('VEGETABLE', 'tikvica', '\mtikvic(a|e|u|om)?\M'),
        ('VEGETABLE', 'tikvice', '\mtikvic(a|e|u|om)?\M'),
        ('VEGETABLE', 'bundeva', '\mbundev(a|e|u|om)?\M'),
        ('VEGETABLE', 'bundevu', '\mbundev(a|e|u|om)?\M'),
        ('VEGETABLE', 'patlidzan', '\mpatlidzan[a-z]*\M'),
        ('VEGETABLE', 'plavi patlidzan', '\mpatlidzan[a-z]*\M'),
        ('VEGETABLE', 'spanac', '\m(spanac(a)?|spinac[a-z]*)\M'),
        ('VEGETABLE', 'blitva', '\mblitv(a|e|u)\M'),
        ('VEGETABLE', 'salata', '\msalat(a|e|u|om)?\M'),
        ('VEGETABLE', 'zelena salata', '\m(zelen(a|e) salat[a-z]*|salat[a-z]* zelen(a|e)|kristalk(a|e)|puterica|iceberg|ajsberg|lolo|lollo|romana)\M'),
        ('VEGETABLE', 'pecurke', '\m(pecurk[a-z]*|sampinjon[a-z]*|bukovac(a|e|u)|shiitake|siitake|sitake|portobel[a-z]*|vrganj[a-z]*)\M'),
        ('VEGETABLE', 'pecurka', '\m(pecurk[a-z]*|sampinjon[a-z]*|bukovac(a|e|u)|shiitake|siitake|sitake|portobel[a-z]*|vrganj[a-z]*)\M'),
        ('VEGETABLE', 'sampinjoni', '\msampinjon[a-z]*\M'),
        ('VEGETABLE', 'rotkvice', '\m(rotkv(a|e|u)|rotkvic(a|e|u))\M'),
        ('VEGETABLE', 'rotkva', '\m(rotkv(a|e|u)|rotkvic(a|e|u))\M'),
        ('VEGETABLE', 'djumbir', '\m(djumbir|dumbir)\M'),
        ('VEGETABLE', 'mirodjija', '\mmirodjij[a-z]*\M')
) AS word (intent_code, alias, pattern)
JOIN app.shopping_intent AS intent
  ON intent.code = word.intent_code
ON CONFLICT (normalized_alias) DO NOTHING;

UPDATE app.shopping_intent_alias AS alias
SET required_name_pattern = '^(?!.*\m(marin[a-z]*|zamr[a-z]*|zamrz[a-z]*|smrz[a-z]*|smrzn[a-z]*|sous'
                            || '|kuvan[a-z]*|pecen[a-z]*|pikant[a-z]*|bbq|dim|dimlj[a-z]*|panir[a-z]*'
                            || '|pohovan[a-z]*|punjen[a-z]*|giros|raznjic[a-z]*|nog[a-z]*|papci|glav(a|e)|rep'
                            || '|pilec[a-z]* ledja|ledja pilec[a-z]*|karlic[a-z]*|vrhov[a-z]*|iznutric[a-z]*'
                            || '|skembic[a-z]*|jetr[a-z]*|dzigeric[a-z]*|srce|srca|bubreg|bubrezi|zelud[a-z]*'
                            || '|mozak|jezik|jezici|skram[a-z]*)\M)'
                            -- Bones and skin, but not a cut "bez kosti" or "sa kožom".
                            || '(?!.*(?<!bez )(?<!sa )\m(kost|kosti|koske|koz(a|e|ura))\M)'
                            -- Nor a pack under 300 g.
                            || '(?!.*\m([1-9][0-9]?|[12][0-9][0-9]) (g|gr|grama)\M)'
FROM app.shopping_intent AS intent
WHERE intent.id = alias.shopping_intent_id
  AND intent.code = 'MEAT'
  AND alias.normalized_alias IN ('meso', 'sveze meso');

-- A list written as "voće" or "povrće" keeps its item, now of the new kind.
UPDATE app.shopping_list_item AS item
SET shopping_intent_id = alias.shopping_intent_id,
    updated_at = NOW()
FROM app.shopping_intent_alias AS alias,
     app.shopping_intent AS old_intent
WHERE old_intent.code = 'FRESH_PRODUCE'
  AND item.shopping_intent_id = old_intent.id
  AND item.matching_rule = 'FLEXIBLE_CATEGORY'
  AND item.flexible_category_normalized = alias.normalized_alias
  AND alias.shopping_intent_id <> old_intent.id;

UPDATE app.shopping_intent
SET active = FALSE,
    updated_at = NOW()
WHERE code = 'FRESH_PRODUCE';

-- Types are made again under the new rules. A confirmed "voće i povrće"
-- becomes the half its name says, and one removed from it stays removed from
-- both halves.
INSERT INTO app.retailer_product_type_rejection (retailer_product_id, product_type_id)
SELECT rejected.retailer_product_id, new_type.id
FROM app.retailer_product_type_rejection AS rejected
JOIN app.product_type AS old_type
  ON old_type.id = rejected.product_type_id
 AND old_type.code = 'FRESH_PRODUCE'
CROSS JOIN app.product_type AS new_type
WHERE new_type.code IN ('FRUIT', 'VEGETABLE')
ON CONFLICT DO NOTHING;

DELETE FROM app.retailer_product_type AS assignment
USING app.product_type AS type
WHERE type.id = assignment.product_type_id
  AND type.code IN ('MEAT', 'FRESH_PRODUCE')
  AND assignment.reviewed = FALSE;

UPDATE app.retailer_product_type AS assignment
SET product_type_id = rule.product_type_id,
    updated_at = NOW()
FROM app.product_type AS old_type,
     app.retailer_product AS product,
     app.product_type_rule AS rule,
     app.product_type AS new_type
WHERE old_type.id = assignment.product_type_id
  AND old_type.code = 'FRESH_PRODUCE'
  AND product.id = assignment.retailer_product_id
  AND new_type.id = rule.product_type_id
  AND new_type.code IN ('FRUIT', 'VEGETABLE')
  AND rule.active
  AND product.normalized_name ~ rule.include_pattern
  AND product.normalized_name !~ rule.exclude_pattern;

SELECT app.assign_generic_product_types(NULL);

-- Suggestions under the old rules go. A type that needs the chain's category
-- is suggested only for a product the chain filed nowhere: one filed under
-- another category is not salt, meat or fruit, and the 86 salt suggestions
-- waiting were dishwasher salt, pads and coffee capsules.
DELETE FROM app.product_type_candidate AS candidate
USING app.product_type AS type
WHERE type.id = candidate.product_type_id
  AND type.code IN ('MEAT', 'FRESH_PRODUCE')
  AND candidate.status = 'PENDING';

DELETE FROM app.product_type_candidate AS candidate
USING app.product_type AS type,
      app.retailer_product AS product
WHERE type.id = candidate.product_type_id
  AND type.needs_source_category = TRUE
  AND product.id = candidate.retailer_product_id
  AND NULLIF(BTRIM(product.category_code), '') IS NOT NULL
  AND candidate.status = 'PENDING';

INSERT INTO app.product_type_candidate (
    retailer_product_id,
    product_type_id,
    confidence,
    prediction_source,
    evidence,
    algorithm_version
)
SELECT prediction.retailer_product_id,
       prediction.product_type_id,
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.predict_product_types(NULL) AS prediction
JOIN app.product_type AS type
  ON type.id = prediction.product_type_id
 AND type.code IN ('MEAT', 'FRUIT', 'VEGETABLE')
JOIN app.retailer_product AS product
  ON product.id = prediction.retailer_product_id
 AND NULLIF(BTRIM(product.category_code), '') IS NULL
WHERE prediction.confidence >= 0.7500
  AND prediction.confidence < 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type AS assignment
      WHERE assignment.retailer_product_id = prediction.retailer_product_id
        AND (assignment.reviewed OR assignment.product_type_id = prediction.product_type_id)
  )
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type_rejection AS rejected
      WHERE rejected.retailer_product_id = prediction.retailer_product_id
        AND rejected.product_type_id = prediction.product_type_id
  )
ON CONFLICT (retailer_product_id, product_type_id, algorithm_version) DO NOTHING;

-- Each affected family shows the type most of its products have.
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM app.retailer_product AS product
    WHERE product.product_family_id IS NOT NULL
      AND (product.category_code IN ('3', '8') OR product.category_code IS NULL)
    UNION
    SELECT family.id
    FROM app.product_family AS family
    JOIN app.product_type AS type
      ON type.id = family.product_type_id
    WHERE type.code IN ('MEAT', 'FRESH_PRODUCE')
) AS affected
LEFT JOIN LATERAL (
    SELECT assignment.product_type_id
    FROM app.retailer_product AS product
    JOIN app.retailer_product_type AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id = affected.id
    GROUP BY assignment.product_type_id
    ORDER BY COUNT(*) DESC,
             MAX(assignment.confidence) DESC,
             assignment.product_type_id
    LIMIT 1
) AS choice ON TRUE
WHERE family.id = affected.id
  AND family.product_type_id IS DISTINCT FROM choice.product_type_id;
