-- Vrste koje spisak imenuje drugačije nego radnje (peti test, 1.11.2026.):
--
-- * "hrana za bebe", "papirni ubrusi" i "hrana za mačke" nisu bile reči na
--   spisku, pa ih je plan tražio po nazivu proizvoda. Radnje ih tako ne
--   zovu: „HIPP kašica jabuka“, „Paloma ubrus 2/1“, „Whiskas govedina“. Zato
--   ih radnje u centru nisu imale, iako ih pretraga nalazi. Sad su vrste, sa
--   pravilima po nazivima koje lanci pišu, i reči na spisku.
-- * "tortilje" su postale tortilja čips sa kečapom, a "energetsko piće 4"
--   nije nađeno. Tortilje (wraps) i energetska pića su vrste.
-- * "parmezan" je postao „Parmezan salama slajs“: tražen je po nazivu, a
--   salama počinje istom reči. Parmezan je sad sir, kao gauda i trapist, i
--   bira se samo među sirevima.

-- Nove vrste proizvoda -----------------------------------------------------

-- Bez kategorije vrste, kao sapun i kečap (V139): lanci ih svrstavaju u
-- bebe, kućnu hemiju, ljubimce, pekaru ili pića, pa kategorija ne sme da
-- obori sigurnost ispod praga.
INSERT INTO app.product_type (code, name, product_category_id)
VALUES
    ('BABY_FOOD', 'Hrana za bebe', NULL),
    ('PAPER_TOWEL', 'Papirni ubrus', NULL),
    ('CAT_FOOD', 'Hrana za mačke', NULL),
    ('DOG_FOOD', 'Hrana za pse', NULL),
    ('TORTILLA', 'Tortilje', NULL),
    ('ENERGY_DRINK', 'Energetsko piće', NULL)
ON CONFLICT (code) DO NOTHING;

INSERT INTO app.product_type_rule (
    product_type_id,
    include_pattern,
    exclude_pattern,
    priority,
    confidence
)
SELECT type.id, rule.include_pattern, rule.exclude_pattern, rule.priority, 0.9600
FROM (
    VALUES
        (
            'BABY_FOOD',
            -- Kašice i obroci za bebe; brendovi koji prave samo hranu za bebe.
            '\mkasic[a-z]*\M|\m(hipp|bebivita|gerber|humana|nutrino)\M'
                || '|^(?=.*\m(beb[a-z]*|baby|bebi)\M).*\m(hran[a-z]*|obrok[a-z]*|meni|menu|pire|kasa|kase'
                || '|zitaric[a-z]*|pahuljic[a-z]*|food)\M',
            -- HIPP pravi i šampon, kreme i maramice; kašičica je pribor.
            '\m(sampon[a-z]*|kupk[a-z]*|krem[a-z]*|ulj[a-z]*|losion[a-z]*|maramic[a-z]*|pelen[a-z]*|gel'
                || '|sapun[a-z]*|puder[a-z]*|pasta|zub[a-z]*|cetk[a-z]*|cetkic[a-z]*|flasic[a-z]*|cucl[a-z]*'
                || '|cinij[a-z]*|tanjir[a-z]*|kasicic[a-z]*|kasik[a-z]*|posud[a-z]*|igrack[a-z]*'
                || '|deterdzent[a-z]*|omeksivac[a-z]*|ves|vesa|sprej[a-z]*|balzam[a-z]*|zastit[a-z]*'
                || '|sunc[a-z]*|termometar[a-z]*|kesa|kese|torb[a-z]*|prasic[a-z]*|mack[a-z]*|pas|psa|pse|dog|cat'
                -- Slatkiši za decu: „BOMBONE FAST FOOD 30G BEBETO“.
                || '|bombon[a-z]*|bebeto|lizalic[a-z]*|zvak[a-z]*|gumen[a-z]*|bonbon[a-z]*)\M',
            -- Ispred voća, jogurta i sokova: „HIPP kašica jabuka“ je hrana za bebe.
            5
        ),
        (
            'PAPER_TOWEL',
            '\mubrus[a-z]*\M|\mkuhinjsk[a-z]* (papir[a-z]*|rolne|rolni|rolna|role)\M'
                || '|\mpapirn[a-z]* (rucnik[a-z]*|rucnic[a-z]*)\M',
            -- Platneni ubrus, krpa i držač nisu papirni ubrus.
            '\m(pamuc[a-z]*|pamuk|frotir[a-z]*|tekstil[a-z]*|lanen[a-z]*|mikrofiber[a-z]*|krp[a-z]*'
                || '|vlazn[a-z]*|drzac[a-z]*|stalak|dozer[a-z]*|dispenzer[a-z]*|toalet[a-z]*|wc|plaz[a-z]*'
                || '|kupatil[a-z]*|kupanj[a-z]*|kos[ae]|tus|kecelj[a-z]*|podmetac[a-z]*|set)\M',
            5
        ),
        (
            'CAT_FOOD',
            -- Brendovi samo za mačke; inače reč za hranu i reč za mačku.
            '\m(whiskas|felix|friskies|sheba|kitekat|perfect fit|cat chow|catessy|gourmet gold)\M'
                || '|^(?=.*\m(hran[a-z]*|granul[a-z]*|kesic[a-z]*|konzerv[a-z]*|pastet[a-z]*|obrok[a-z]*'
                || '|briket[a-z]*|poslastic[a-z]*|food)\M).*\m(mack[a-z]*|mace|macj[a-z]*|macij[a-z]*|cat|cats'
                || '|kitten|mac)\M',
            -- Pesak, posip i pribor nisu hrana; hrana za pse nije za mačke.
            '\m(pesak|peska|posip[a-z]*|igrack[a-z]*|grebalic[a-z]*|ogrlic[a-z]*|posud[a-z]*|cinij[a-z]*'
                || '|toalet[a-z]*|sampon[a-z]*|cetk[a-z]*|transporter[a-z]*|krevet[a-z]*|kavez[a-z]*'
                || '|pas|psa|pse|psi|dog|dogs|pseci|pasj[a-z]*|puppy|kesa|kese)\M',
            -- Ispred ribe i mesa: „Whiskas losos“ je hrana za mačke.
            5
        ),
        (
            'DOG_FOOD',
            '\m(pedigree|chappi|frolic|dog chow|dentastix)\M'
                || '|^(?=.*\m(hran[a-z]*|granul[a-z]*|kesic[a-z]*|konzerv[a-z]*|pastet[a-z]*|obrok[a-z]*'
                || '|briket[a-z]*|poslastic[a-z]*|food|zvakalic[a-z]*)\M).*\m(pas|psa|pse|psi|pseci|pasj[a-z]*'
                || '|dog|dogs|puppy)\M',
            '\m(pesak|posip[a-z]*|igrack[a-z]*|ogrlic[a-z]*|povod[a-z]*|posud[a-z]*|cinij[a-z]*'
                || '|sampon[a-z]*|cetk[a-z]*|transporter[a-z]*|krevet[a-z]*|kavez[a-z]*|pelen[a-z]*'
                || '|mack[a-z]*|mace|macj[a-z]*|cat|cats|kitten|kesa|kese)\M',
            5
        ),
        (
            'TORTILLA',
            '\m(tortilj[a-z]*|tortill[a-z]*|wraps?)\M',
            -- Tortilja čips i gotova jela u tortilji nisu tortilje.
            '\m(cips[a-z]*|chips|chip|nachos?|snack[a-z]*|grickalic[a-z]*|ukus[a-z]*|arom[a-z]*'
                || '|kecap[a-z]*|ketchup|paprik[a-z]*|chili|cili|salsa|sos[a-z]*|dip|sour|pavlak[a-z]*|bbq'
                || '|so|morska|piletin[a-z]*|pilec[a-z]*|sunk[a-z]*|sir|sirom|povrc[a-z]*|meso|mesom'
                || '|gotov[a-z]*|obrok[a-z]*|sendvic[a-z]*|burrito|fajita|kebab|giros|tun[ae]|losos[a-z]*'
                || '|salat[a-z]*|tiganj[a-z]*|kalup[a-z]*'
                -- Grickalice koje lanci zovu tortiljom, i ulošci „Single Wrap“.
                || '|cheese|pizza|kechup|kecup|hot|spicy|flavour|sourcream|cream|luk|tzatziki|rostilj'
                || '|cvarc[a-z]*|famoso|doritos|smoki|chio|miguel|hrusty|patos|always|liners?|ulos[a-z]*'
                || '|discreet|dnevn[a-z]*)\M',
            -- Ispred hleba i grickalica.
            5
        ),
        (
            'ENERGY_DRINK',
            -- Samo piće: „KROASAN MONSTER KAKAO“ nije energetsko piće.
            '^(?=.*\m(ml|l|cl|lit[a-z]*|napit[a-z]*|napic[a-z]*|pice|drink|limenk[a-z]*|can)\M).*'
                || '\m(energ[a-z]*|energy|red bull|redbull|monster|guarana|hell|burn|rockstar|power horse'
                || '|powerhorse|shark)\M',
            '\m(plocic[a-z]*|bar|cokolad[a-z]*|bombon[a-z]*|zvak[a-z]*|gel|tablet[a-z]*|sumec[a-z]*'
                || '|kapsul[a-z]*|keks[a-z]*|musli|zitn[a-z]*|kroasan[a-z]*|sampon[a-z]*|dezodorans[a-z]*'
                || '|sprej[a-z]*|baterij[a-z]*|sijalic[a-z]*|kafa|caj|protein[a-z]*|izotonik[a-z]*'
                || '|sladoled[a-z]*|pivo|rakij[a-z]*|vodk[a-z]*|liker[a-z]*|koktel[a-z]*|case|casa'
                -- Kozmetika „Energy“: „Dove Men Energising 150ml“, „Nivea SG Energy“.
                || '|deo|dezodor[a-z]*|antiperspir[a-z]*|roll|tus[a-z]*|kupk[a-z]*|losion[a-z]*|krem[a-z]*'
                || '|parfem[a-z]*|balzam[a-z]*|brijanj[a-z]*|sg|as|edt|edp|men|nivea|dove|loreal|axe'
                || '|rexona|garnier|cardin|zensen|energiser|energizer)\M',
            -- Ispred gaziranih sokova: „Guarana“ je energetsko piće.
            5
        ),
        (
            'CHEESE',
            -- Sirevi koje lanci zovu samo po vrsti, bez reči "sir":
            -- „ZANETTI PARMIGIANO REGGIANO 250G“.
            '\m(parmezan[a-z]*|parmigiano|grana padano|pecorino|gorgonzol[a-z]*|ementaler|emmental[a-z]*'
                || '|cheddar|camembert|kamamber|brie|feta|halloumi|maasdam[a-z]*|kackavalj[a-z]*|tilsiter'
                || '|rikot[a-z]*|ricotta|mascarpone|maskarpone)\M',
            -- „Parmezan salama“, pica, testenina i grickalice sa sirom nisu sir.
            '\m(salam[a-z]*|kobasic[a-z]*|sunk[a-z]*|prsut[a-z]*|slanin[a-z]*|kulen[a-z]*|pecenic[a-z]*'
                || '|virsl[a-z]*|cips[a-z]*|chips|kreker[a-z]*|grisin[a-z]*|stapic[a-z]*|sos[a-z]*|pesto'
                || '|preliv[a-z]*|zacin[a-z]*|testenin[a-z]*|njok[a-z]*|ravioli|tortelin[a-z]*|tortellini'
                || '|pica|pizza|pizz[a-z]*|panin[a-z]*|sendvic[a-z]*|burek[a-z]*|pita|pite|kroasan[a-z]*'
                || '|pogac[a-z]*|kiflic[a-z]*|pljeskavic[a-z]*|burger[a-z]*|pohovan[a-z]*|rolnic[a-z]*'
                || '|lisnat[a-z]*|salat[a-z]*|supa|corb[a-z]*|ukus[a-z]*|arom[a-z]*|hrana|pas|psa|pse'
                || '|macke|rende|ljustac[a-z]*|noz)\M',
            20
        )
) AS rule (type_code, include_pattern, exclude_pattern, priority)
JOIN app.product_type AS type
  ON type.code = rule.type_code;

-- Pravila su se promenila: predviđanja nose novu verziju.
DO $$
BEGIN
    EXECUTE REPLACE(
        pg_get_functiondef('app.predict_product_types(bigint)'::regprocedure),
        'taxonomy-v19',
        'taxonomy-v20'
    );
END $$;

-- Reči na spisku -----------------------------------------------------------

-- Ubrus se meri u rolnama („2/1“), kao toalet papir (V126).
INSERT INTO app.shopping_intent (
    code,
    name,
    default_min_package_quantity,
    default_max_package_quantity,
    default_base_unit
)
VALUES
    ('BABY_FOOD', 'Hrana za bebe', NULL, NULL, NULL),
    ('PAPER_TOWEL', 'Papirni ubrus', 2, 4, NULL),
    ('CAT_FOOD', 'Hrana za mačke', NULL, NULL, NULL),
    ('DOG_FOOD', 'Hrana za pse', NULL, NULL, NULL),
    ('TORTILLA', 'Tortilje', 200, 500, 'g'),
    ('ENERGY_DRINK', 'Energetsko piće', 250, 500, 'ml')
ON CONFLICT (code) DO NOTHING;

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
        ('BABY_FOOD', 'BABY_FOOD'),
        ('PAPER_TOWEL', 'PAPER_TOWEL'),
        ('CAT_FOOD', 'CAT_FOOD'),
        ('DOG_FOOD', 'DOG_FOOD'),
        ('TORTILLA', 'TORTILLA'),
        ('ENERGY_DRINK', 'ENERGY_DRINK')
) AS allowed (intent_code, type_code)
JOIN app.shopping_intent AS intent
  ON intent.code = allowed.intent_code
JOIN app.product_type AS type
  ON type.code = allowed.type_code
ON CONFLICT (shopping_intent_id, product_type_id) DO NOTHING;

INSERT INTO app.shopping_intent_alias (
    shopping_intent_id,
    normalized_alias,
    priority,
    required_name_pattern,
    uncommon_name_pattern
)
SELECT intent.id, word.alias, 5, word.required, word.uncommon
FROM (
    VALUES
        -- Obično je kašica ili obrok; mleko za bebe, čaj i keks tek kad nema drugog.
        ('BABY_FOOD', 'hrana za bebe', NULL,
            '\m(mlek[a-z]*|formul[a-z]*|pocetn[a-z]*|caj[a-z]*|sok[a-z]*|keks[a-z]*|griz)\M'),
        ('BABY_FOOD', 'hrana za bebu', NULL,
            '\m(mlek[a-z]*|formul[a-z]*|pocetn[a-z]*|caj[a-z]*|sok[a-z]*|keks[a-z]*|griz)\M'),
        ('BABY_FOOD', 'bebi hrana', NULL,
            '\m(mlek[a-z]*|formul[a-z]*|pocetn[a-z]*|caj[a-z]*|sok[a-z]*|keks[a-z]*|griz)\M'),
        ('BABY_FOOD', 'decja hrana', NULL,
            '\m(mlek[a-z]*|formul[a-z]*|pocetn[a-z]*|caj[a-z]*|sok[a-z]*|keks[a-z]*|griz)\M'),
        ('BABY_FOOD', 'kasica', '\mkasic[a-z]*\M', NULL),
        ('BABY_FOOD', 'kasice', '\mkasic[a-z]*\M', NULL),
        ('BABY_FOOD', 'kasicu', '\mkasic[a-z]*\M', NULL),
        ('BABY_FOOD', 'kasica za bebe', '\mkasic[a-z]*\M', NULL),
        ('BABY_FOOD', 'kasice za bebe', '\mkasic[a-z]*\M', NULL),
        ('PAPER_TOWEL', 'ubrus', NULL, NULL),
        ('PAPER_TOWEL', 'ubrusi', NULL, NULL),
        ('PAPER_TOWEL', 'ubruse', NULL, NULL),
        ('PAPER_TOWEL', 'papirni ubrus', NULL, NULL),
        ('PAPER_TOWEL', 'papirni ubrusi', NULL, NULL),
        ('PAPER_TOWEL', 'papirne ubruse', NULL, NULL),
        ('PAPER_TOWEL', 'kuhinjski ubrus', NULL, NULL),
        ('PAPER_TOWEL', 'kuhinjski ubrusi', NULL, NULL),
        ('PAPER_TOWEL', 'kuhinjske ubruse', NULL, NULL),
        ('PAPER_TOWEL', 'kuhinjski papir', NULL, NULL),
        ('PAPER_TOWEL', 'kuhinjske rolne', NULL, NULL),
        ('PAPER_TOWEL', 'papirni rucnici', NULL, NULL),
        -- Poslastice i dodaci nisu obrok.
        ('CAT_FOOD', 'hrana za macke', NULL,
            '\m(poslastic[a-z]*|dreamies|stapic[a-z]*|snack[a-z]*|treats?|mleko|vitamin[a-z]*|pasta)\M'),
        ('CAT_FOOD', 'hrana za macku', NULL,
            '\m(poslastic[a-z]*|dreamies|stapic[a-z]*|snack[a-z]*|treats?|mleko|vitamin[a-z]*|pasta)\M'),
        ('CAT_FOOD', 'hrana za mace', NULL,
            '\m(poslastic[a-z]*|dreamies|stapic[a-z]*|snack[a-z]*|treats?|mleko|vitamin[a-z]*|pasta)\M'),
        ('CAT_FOOD', 'macja hrana', NULL,
            '\m(poslastic[a-z]*|dreamies|stapic[a-z]*|snack[a-z]*|treats?|mleko|vitamin[a-z]*|pasta)\M'),
        ('CAT_FOOD', 'macija hrana', NULL,
            '\m(poslastic[a-z]*|dreamies|stapic[a-z]*|snack[a-z]*|treats?|mleko|vitamin[a-z]*|pasta)\M'),
        ('CAT_FOOD', 'granule za macke', '\m(granul[a-z]*|suv[a-z]*|dry|briket[a-z]*)\M', NULL),
        ('CAT_FOOD', 'suva hrana za macke', '\m(granul[a-z]*|suv[a-z]*|dry|briket[a-z]*)\M', NULL),
        ('DOG_FOOD', 'hrana za pse', NULL,
            '\m(poslastic[a-z]*|kost[a-z]*|stapic[a-z]*|zvakalic[a-z]*|snack[a-z]*|treats?|dentastix|vitamin[a-z]*)\M'),
        ('DOG_FOOD', 'hrana za psa', NULL,
            '\m(poslastic[a-z]*|kost[a-z]*|stapic[a-z]*|zvakalic[a-z]*|snack[a-z]*|treats?|dentastix|vitamin[a-z]*)\M'),
        ('DOG_FOOD', 'pseca hrana', NULL,
            '\m(poslastic[a-z]*|kost[a-z]*|stapic[a-z]*|zvakalic[a-z]*|snack[a-z]*|treats?|dentastix|vitamin[a-z]*)\M'),
        ('DOG_FOOD', 'granule za pse', '\m(granul[a-z]*|suv[a-z]*|dry|briket[a-z]*)\M', NULL),
        ('DOG_FOOD', 'granule za psa', '\m(granul[a-z]*|suv[a-z]*|dry|briket[a-z]*)\M', NULL),
        ('TORTILLA', 'tortilje', NULL, '\m(mini)\M'),
        ('TORTILLA', 'tortilja', NULL, '\m(mini)\M'),
        ('TORTILLA', 'tortilju', NULL, '\m(mini)\M'),
        ('TORTILLA', 'tortilla', NULL, '\m(mini)\M'),
        ('TORTILLA', 'tortillas', NULL, '\m(mini)\M'),
        ('TORTILLA', 'wraps', NULL, '\m(mini)\M'),
        ('TORTILLA', 'wrap', NULL, '\m(mini)\M'),
        ('ENERGY_DRINK', 'energetsko pice', NULL, NULL),
        ('ENERGY_DRINK', 'energetska pica', NULL, NULL),
        ('ENERGY_DRINK', 'energetski napitak', NULL, NULL),
        ('ENERGY_DRINK', 'energetski napici', NULL, NULL),
        ('ENERGY_DRINK', 'energetsko', NULL, NULL),
        ('ENERGY_DRINK', 'energetski', NULL, NULL),
        ('ENERGY_DRINK', 'energy drink', NULL, NULL),
        -- Parmezan se bira samo među sirevima koji to kažu.
        ('CHEESE', 'parmezan', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL),
        ('CHEESE', 'parmezana', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL),
        ('CHEESE', 'sir parmezan', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL),
        ('CHEESE', 'parmezan sir', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL),
        ('CHEESE', 'rendani parmezan', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL),
        ('CHEESE', 'parmezan rendani', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL),
        ('CHEESE', 'parmigiano', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL),
        ('CHEESE', 'parmigiano reggiano', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL),
        ('CHEESE', 'grana padano', '\m(parmezan[a-z]*|parmigiano|grana|reggiano)\M', NULL)
) AS word (intent_code, alias, required, uncommon)
JOIN app.shopping_intent AS intent
  ON intent.code = word.intent_code
ON CONFLICT (normalized_alias) DO NOTHING;

-- Vrste po novim pravilima ---------------------------------------------------

CREATE TEMPORARY TABLE named_kind ON COMMIT DROP AS
SELECT prediction.retailer_product_id,
       prediction.product_type_id,
       type.code AS type_code,
       prediction.confidence,
       prediction.prediction_source,
       prediction.evidence,
       prediction.algorithm_version
FROM app.predict_product_types(NULL) AS prediction
JOIN app.product_type AS type
  ON type.id = prediction.product_type_id
 AND type.code IN ('BABY_FOOD', 'PAPER_TOWEL', 'CAT_FOOD', 'DOG_FOOD', 'TORTILLA', 'ENERGY_DRINK', 'CHEESE')
WHERE prediction.confidence >= 0.9500
  AND NOT EXISTS (
      SELECT 1
      FROM app.retailer_product_type_rejection AS rejected
      WHERE rejected.retailer_product_id = prediction.retailer_product_id
        AND rejected.product_type_id = prediction.product_type_id
  );

-- „HIPP kašica jabuka“ je mašinski bila voće, „Whiskas losos“ riba, a
-- „Guarana“ gazirani sok. Mašinska vrsta takvog proizvoda se menja; ručno
-- potvrđena ostaje. Sir menja samo proizvode bez vrste.
DELETE FROM app.retailer_product_type AS assignment
USING named_kind AS prediction
WHERE assignment.retailer_product_id = prediction.retailer_product_id
  AND assignment.product_type_id <> prediction.product_type_id
  AND prediction.type_code <> 'CHEESE'
  AND assignment.reviewed = FALSE;

INSERT INTO app.retailer_product_type (
    retailer_product_id,
    product_type_id,
    confidence,
    assignment_source,
    evidence,
    algorithm_version
)
SELECT retailer_product_id,
       product_type_id,
       confidence,
       prediction_source,
       evidence,
       algorithm_version
FROM named_kind
ON CONFLICT (retailer_product_id) DO NOTHING;

-- Svaka porodica proizvoda u ovim vrstama pokazuje vrstu koju ima većina.
UPDATE app.product_family AS family
SET product_type_id = choice.product_type_id,
    updated_at = NOW()
FROM (
    SELECT DISTINCT product.product_family_id AS id
    FROM app.retailer_product AS product
    JOIN named_kind AS assignment
      ON assignment.retailer_product_id = product.id
    WHERE product.product_family_id IS NOT NULL
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
