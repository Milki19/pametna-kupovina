package rs.pametnakupovina.backend.testskup;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.TestCallers;
import rs.pametnakupovina.backend.account.AccountRepository;
import rs.pametnakupovina.backend.security.DeviceCaller;
import rs.pametnakupovina.backend.shoppinglist.CreateShoppingListRequest;
import rs.pametnakupovina.backend.shoppinglist.OptimizationScenarioResponse;
import rs.pametnakupovina.backend.shoppinglist.PasteShoppingListItemsRequest;
import rs.pametnakupovina.backend.shoppinglist.PasteShoppingListItemsResponse;
import rs.pametnakupovina.backend.shoppinglist.RecommendationItemResponse;
import rs.pametnakupovina.backend.shoppinglist.RecommendationItemStatus;
import rs.pametnakupovina.backend.shoppinglist.ResolveShoppingItemMatchRequest;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemMatchAction;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemMatchResult;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemMatchingStatus;
import rs.pametnakupovina.backend.shoppinglist.ShoppingItemRule;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListItemResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListMatchingResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListMatchingService;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListService;
import rs.pametnakupovina.backend.shoppinglist.ShoppingListTextParser;
import rs.pametnakupovina.backend.shoppinglist.ParsedShoppingListLine;
import rs.pametnakupovina.backend.shoppinglist.ShoppingRecommendationResponse;
import rs.pametnakupovina.backend.shoppinglist.ShoppingRecommendationService;
import rs.pametnakupovina.backend.shoppinglist.TravelMode;
import rs.pametnakupovina.backend.shoppinglist.UpdateShoppingListItemRequest;
import rs.pametnakupovina.backend.shoppinglist.FlexibleItemConstraints;
import rs.pametnakupovina.backend.testskup.TestSkup.Lokacija;
import rs.pametnakupovina.backend.testskup.TestSkup.Ocena;
import rs.pametnakupovina.backend.testskup.TestSkup.Stavka;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/**
 * Pastes the test set into throwaway lists (one per real list and city, or
 * one per city for the loose lines), runs step 1 and the plan the way the
 * web app does and grades three things apart: how the parser read the
 * amount, what step 1 settled on, and what the plan picked. The lists and
 * the made-up phone are erased afterwards.
 */
@Component
public class TestSkupMerac {

    private static final int IZRACUNAJ_STAVKI = 30;

    private final TestCallers callers;
    private final AccountRepository accountRepository;
    private final ShoppingListService shoppingListService;
    private final ShoppingListMatchingService matchingService;
    private final ShoppingRecommendationService recommendationService;
    private final JdbcClient jdbcClient;

    public TestSkupMerac(
            TestCallers callers,
            AccountRepository accountRepository,
            ShoppingListService shoppingListService,
            ShoppingListMatchingService matchingService,
            ShoppingRecommendationService recommendationService,
            JdbcClient jdbcClient
    ) {
        this.callers = callers;
        this.accountRepository = accountRepository;
        this.shoppingListService = shoppingListService;
        this.matchingService = matchingService;
        this.recommendationService = recommendationService;
        this.jdbcClient = jdbcClient;
    }

    /**
     * Percentages per city, and where the report went. {@code prepoznato} is
     * the share of lines with a product in the plan, {@code ispravno} the
     * share whose product is right, {@code korak1} the share step 1 read as
     * the right product or kind, and {@code citanje} the share of lines with
     * an amount in the set whose amount the parser read right (null when the
     * set gives none).
     */
    public record Ishod(
            Path izvestaj,
            Map<String, Double> prepoznato,
            Map<String, Double> ispravno,
            Map<String, Double> korak1,
            Double citanje
    ) {
    }

    public Ishod meri(TestSkup skup, Path dir) throws IOException {
        return meri(skup, dir, false);
    }

    /**
     * {@code slepo}: the report keeps only the totals, never a line or a
     * product, so it can be shown before the blind part is opened.
     */
    public Ishod meri(TestSkup skup, Path dir, boolean slepo) throws IOException {
        if (skup.lokacije().isEmpty()) {
            throw new IllegalArgumentException("Nema polaznih tačaka za " + skup.market());
        }
        DeviceCaller caller = callers.caller("test-skup-" + UUID.randomUUID());
        jdbcClient.sql("UPDATE app.account SET market_id = (SELECT id FROM app.market WHERE code = :code) WHERE id = :id")
                .param("code", skup.market())
                .param("id", caller.accountId())
                .update();
        List<Long> lists = new ArrayList<>();
        try {
            // Timing first, while nothing from this run is cached yet.
            List<Izracunaj> vremena = new ArrayList<>();
            for (Lokacija lokacija : skup.lokacije()) {
                List<Stavka> uGradu = skup.u(lokacija.grad());
                if (uGradu.size() < IZRACUNAJ_STAVKI && !vremena.isEmpty()) {
                    continue;
                }
                long kratak = list(caller, lists, "Test skup " + IZRACUNAJ_STAVKI);
                shoppingListService.addPastedItems(kratak, caller.accountId(), new PasteShoppingListItemsRequest(
                        uGradu.stream().limit(IZRACUNAJ_STAVKI).map(Stavka::tekst).collect(Collectors.joining("\n"))));
                long t0 = System.nanoTime();
                ShoppingListMatchingResponse matching = matchingService.match(kratak, caller, true);
                long t1 = System.nanoTime();
                int odluka = appChooses(kratak, caller, matching, new LinkedHashMap<>());
                long t2 = System.nanoTime();
                recommend(kratak, lokacija);
                long t3 = System.nanoTime();
                vremena.add(new Izracunaj(lokacija.grad(), Math.min(uGradu.size(), IZRACUNAJ_STAVKI),
                        ms(t1 - t0), ms(t3 - t2), odluka));
            }

            Map<String, Citanje> citanje = new LinkedHashMap<>();
            for (Stavka stavka : skup.stavke()) {
                citanje.put(stavka.id(), Citanje.of(stavka));
            }

            List<Rezultat> rezultati = new ArrayList<>();
            for (Lokacija lokacija : skup.lokacije()) {
                Map<String, List<Stavka>> spiskovi = skup.u(lokacija.grad()).stream()
                        .collect(Collectors.groupingBy(s -> s.spisak() == null ? "" : s.spisak(),
                                LinkedHashMap::new, Collectors.toList()));
                for (var spisak : spiskovi.entrySet()) {
                    long listId = list(caller, lists, "Test skup " + lokacija.grad() + " " + spisak.getKey());
                    rezultati.addAll(measure(spisak.getValue(), caller, listId, lokacija, citanje));
                }
            }

            Map<String, Double> prepoznato = new LinkedHashMap<>();
            Map<String, Double> ispravno = new LinkedHashMap<>();
            Map<String, Double> korak1 = new LinkedHashMap<>();
            for (Lokacija lokacija : skup.lokacije()) {
                List<Rezultat> grad = rezultati.stream().filter(r -> r.grad().equals(lokacija.grad())).toList();
                prepoznato.put(lokacija.grad(), percent(grad, r -> r.ocena() != Ocena.NEMA));
                ispravno.put(lokacija.grad(), percent(grad, r -> r.ocena() == Ocena.ISPRAVNO));
                korak1.put(lokacija.grad(), percent(grad, r -> r.prepoznavanje().ocena() == Ocena.ISPRAVNO));
            }
            List<Citanje> sKolicinom = citanje.values().stream().filter(c -> c.ocena() != null).toList();
            Double citano = sKolicinom.isEmpty() ? null
                    : 100.0 * sKolicinom.stream().filter(c -> c.ocena() == Ocena.ISPRAVNO).count() / sKolicinom.size();
            return new Ishod(write(skup, rezultati, citanje, vremena, dir, slepo), prepoznato, ispravno, korak1, citano);
        } finally {
            for (long list : lists) {
                shoppingListService.deleteList(list, caller.accountId());
            }
            accountRepository.forget(caller.clientTokenHash());
        }
    }

    private record Izracunaj(String grad, int stavki, long korak1Ms, long preporukaMs, int odluka) {
        long ukupnoMs() {
            return korak1Ms + preporukaMs;
        }
    }

    private record Izbor(String scenario, String proizvod, String lanac, BigDecimal pakovanja, Ocena ocena, String razlog) {
    }

    /** How the parser read the amount; {@code ocena} is null when the set gives no amount. */
    record Citanje(String procitano, Ocena ocena) {

        private static final ShoppingListTextParser PARSER = new ShoppingListTextParser();

        static Citanje of(Stavka stavka) {
            List<ParsedShoppingListLine> items = PARSER.parse(stavka.tekst()).items();
            if (items.size() != 1) {
                return new Citanje(items.size() + " stavki", stavka.kolicina() == null ? null : Ocena.POGRESNO);
            }
            TestSkup.Kolicina read = TestSkup.Kolicina.of(items.getFirst());
            String text = "„" + items.getFirst().name() + "“ " + read
                    + (items.getFirst().bareNumber() == null ? "" : " (goli broj " + items.getFirst().bareNumber().toPlainString() + ")");
            if (stavka.kolicina() == null) {
                return new Citanje(text, null);
            }
            return new Citanje(text, stavka.kolicina().isti(read) ? Ocena.ISPRAVNO : Ocena.POGRESNO);
        }
    }

    /** What step 1 settled on: a product, a known kind, only the line's words, or nothing. */
    enum Nadjeno { PROIZVOD, VRSTA, RECI, NISTA }

    record Prepoznavanje(Nadjeno nadjeno, String naziv, Ocena ocena) {
    }

    private record Rezultat(
            Stavka stavka,
            String grad,
            String korak1,
            Prepoznavanje prepoznavanje,
            List<Izbor> izbori,
            String bezCene
    ) {

        Ocena ocena() {
            if (izbori.isEmpty()) {
                return Ocena.NEMA;
            }
            return izbori.stream().allMatch(i -> i.ocena() == Ocena.ISPRAVNO) ? Ocena.ISPRAVNO : Ocena.POGRESNO;
        }

        /** Where the line was lost, for the thread that owns that part. */
        String gde(Citanje citanje) {
            if (citanje != null && citanje.ocena() == Ocena.POGRESNO) {
                return "čitanje količine";
            }
            if (prepoznavanje.ocena() != Ocena.ISPRAVNO) {
                return prepoznavanje.nadjeno() == Nadjeno.NISTA ? "korak 1 ništa" : "korak 1 pogrešno";
            }
            return switch (ocena()) {
                case ISPRAVNO -> "ispravno";
                case POGRESNO -> "izbor u planu";
                case NEMA -> "nema cene u blizini";
            };
        }
    }

    private static final List<String> GDE = List.of(
            "ispravno", "čitanje količine", "korak 1 ništa", "korak 1 pogrešno", "izbor u planu", "nema cene u blizini");

    /** Each line is pasted on its own, so a line the parser rejects costs only that line. */
    private List<Rezultat> measure(
            List<Stavka> stavke,
            DeviceCaller caller,
            long listId,
            Lokacija lokacija,
            Map<String, Citanje> citanje
    ) {
        Map<Long, Stavka> stavkaByItem = new LinkedHashMap<>();
        Map<String, String> korak1 = new LinkedHashMap<>();
        for (Stavka stavka : stavke) {
            try {
                PasteShoppingListItemsResponse pasted = shoppingListService
                        .addPastedItems(listId, caller.accountId(), new PasteShoppingListItemsRequest(stavka.tekst()));
                if (pasted.items().isEmpty()) {
                    korak1.put(stavka.id(), "spisak odbio: " + pasted.rejectedLines().stream()
                            .map(PasteShoppingListItemsResponse.RejectedLine::message).collect(Collectors.joining("; ")));
                    continue;
                }
                stavkaByItem.put(pasted.items().getFirst().id(), stavka);
            } catch (RuntimeException e) {
                korak1.put(stavka.id(), "spisak odbio: " + e.getMessage());
            }
        }

        ShoppingListMatchingResponse matching = matchingService.match(listId, caller, true);
        for (ShoppingItemMatchResult item : matching.items()) {
            Stavka stavka = stavkaByItem.get(item.itemId());
            if (stavka != null) {
                korak1.put(stavka.id(), item.matchingRule() == ShoppingItemRule.FLEXIBLE_CATEGORY
                        ? "vrsta, " + status(item.matchingStatus())
                        : "proizvod, " + status(item.matchingStatus()));
            }
        }
        Map<Long, String> odluke = new LinkedHashMap<>();
        appChooses(listId, caller, matching, odluke);
        odluke.forEach((itemId, odluka) -> korak1.merge(stavkaByItem.get(itemId).id(), odluka, (a, b) -> a + "; " + b));
        Map<Long, Prepoznavanje> prepoznavanje = recognised(listId, stavkaByItem);

        ShoppingRecommendationResponse plan = recommend(listId, lokacija);
        Map<Long, List<Izbor>> izbori = new LinkedHashMap<>();
        Map<Long, String> bezCene = new LinkedHashMap<>();
        Stream.of(plan.singleStore(), plan.recommendedBalance(), plan.lowestPrice())
                .filter(Objects::nonNull)
                .forEach(scenario -> collect(scenario, stavkaByItem, izbori, bezCene));

        Map<String, Long> itemByStavka = stavkaByItem.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getValue().id(), Map.Entry::getKey));
        List<Rezultat> rezultati = new ArrayList<>();
        for (Stavka stavka : stavke) {
            Long itemId = itemByStavka.get(stavka.id());
            rezultati.add(new Rezultat(
                    stavka,
                    lokacija.grad(),
                    korak1.getOrDefault(stavka.id(), "?"),
                    itemId == null ? new Prepoznavanje(Nadjeno.NISTA, null, Ocena.NEMA)
                            : prepoznavanje.getOrDefault(itemId, new Prepoznavanje(Nadjeno.NISTA, null, Ocena.NEMA)),
                    itemId == null ? List.of() : izbori.getOrDefault(itemId, List.of()),
                    itemId == null ? null : bezCene.get(itemId)
            ));
        }
        return rezultati;
    }

    /**
     * Step 1 graded on its own: the product it matched (or the family), the
     * kind it settled on, or, for a kind nobody taught it, only the words.
     * Pack counts belong to the plan, so they are not graded here.
     */
    private Map<Long, Prepoznavanje> recognised(long listId, Map<Long, Stavka> stavkaByItem) {
        Map<Long, Prepoznavanje> result = new LinkedHashMap<>();
        jdbcClient.sql("""
                        SELECT item.id,
                               item.matching_rule,
                               product.name AS product,
                               family.display_name AS family,
                               intent.name AS intent,
                               item.flexible_category
                        FROM app.shopping_list_item AS item
                        LEFT JOIN app.canonical_product AS product ON product.id = item.matched_canonical_product_id
                        LEFT JOIN app.product_family AS family ON family.id = item.matched_product_family_id
                        LEFT JOIN app.shopping_intent AS intent ON intent.id = item.shopping_intent_id
                        WHERE item.shopping_list_id = ?
                        """)
                .param(listId)
                .query((row, n) -> {
                    long itemId = row.getLong("id");
                    Stavka stavka = stavkaByItem.get(itemId);
                    if (stavka == null) {
                        return null;
                    }
                    String product = Objects.requireNonNullElse(row.getString("product"), Objects.toString(row.getString("family"), ""));
                    boolean flexible = ShoppingItemRule.FLEXIBLE_CATEGORY.name().equals(row.getString("matching_rule"));
                    Prepoznavanje p;
                    if (!product.isBlank()) {
                        p = new Prepoznavanje(Nadjeno.PROIZVOD, product, stavka.oceni(product, null));
                    } else if (flexible && row.getString("intent") != null) {
                        String intent = row.getString("intent");
                        // A kind's name is short ("Kafa"); what the line said tells it apart from its neighbours.
                        String graded = intent + " " + Objects.toString(row.getString("flexible_category"), "");
                        p = new Prepoznavanje(Nadjeno.VRSTA, intent, stavka.oceni(graded, null));
                    } else if (flexible && row.getString("flexible_category") != null) {
                        String words = row.getString("flexible_category");
                        p = new Prepoznavanje(Nadjeno.RECI, words, stavka.oceni(words, null));
                    } else {
                        p = new Prepoznavanje(Nadjeno.NISTA, null, Ocena.NEMA);
                    }
                    result.put(itemId, p);
                    return p;
                })
                .list();
        return result;
    }

    private void collect(
            OptimizationScenarioResponse scenario,
            Map<Long, Stavka> stavkaByItem,
            Map<Long, List<Izbor>> grad,
            Map<Long, String> razlozi
    ) {
        for (RecommendationItemResponse item : scenario.items()) {
            Stavka stavka = stavkaByItem.get(item.itemId());
            if (stavka == null) {
                continue;
            }
            if (item.resultStatus() != RecommendationItemStatus.AVAILABLE || item.productName() == null) {
                razlozi.putIfAbsent(item.itemId(), item.resultStatus() + (item.explanation() == null ? "" : ": " + item.explanation()));
                continue;
            }
            BigDecimal packages = item.purchaseQuantity() == null ? item.requestedQuantity() : item.purchaseQuantity().packages();
            List<Izbor> izbori = grad.computeIfAbsent(item.itemId(), id -> new ArrayList<>());
            if (izbori.stream().noneMatch(i -> i.proizvod().equals(item.productName()) && Objects.equals(i.lanac(), item.retailerName()))) {
                izbori.add(new Izbor(
                        scenario.type().name(),
                        item.productName(),
                        item.retailerName(),
                        packages,
                        stavka.oceni(item.productName(), packages),
                        stavka.razlog(item.productName(), packages)
                ));
            }
        }
    }

    /**
     * What a shopper who does not want to decide does in step 1: "Neka
     * aplikacija izabere" for an unknown product, the first suggestion for an
     * unclear one, and "Nijedan" when neither is possible.
     */
    private int appChooses(long listId, DeviceCaller caller, ShoppingListMatchingResponse matching, Map<Long, String> odluke) {
        int count = 0;
        for (ShoppingItemMatchResult item : matching.items()) {
            if (!item.blocksOptimization()
                    && !(item.matchingRule() == ShoppingItemRule.EXACT_PRODUCT && item.matchingStatus() == ShoppingItemMatchingStatus.UNMATCHED)) {
                continue;
            }
            count++;
            if (item.matchingStatus() == ShoppingItemMatchingStatus.NEEDS_CONFIRMATION && !item.candidates().isEmpty()) {
                var first = item.candidates().getFirst();
                try {
                    matchingService.resolve(listId, item.itemId(), caller, new ResolveShoppingItemMatchRequest(
                            ShoppingItemMatchAction.CONFIRM, first.canonicalProductId(), "Test skup: prvi predlog",
                            first.canonicalProductId() == null ? first.productFamilyId() : null));
                    odluke.put(item.itemId(), "potvrđen prvi predlog: " + first.name());
                    continue;
                } catch (RuntimeException e) {
                    odluke.put(item.itemId(), "potvrda nije uspela: " + e.getMessage());
                }
            }
            ShoppingListItemResponse stored = shoppingListService.findById(listId, caller.accountId()).items().stream()
                    .filter(i -> i.id().equals(item.itemId())).findFirst().orElseThrow();
            try {
                shoppingListService.updateItem(listId, item.itemId(), caller.accountId(), new UpdateShoppingListItemRequest(
                        stored.name(), stored.rawInput(), null, stored.quantity(), ShoppingItemRule.FLEXIBLE_CATEGORY,
                        new FlexibleItemConstraints(stored.name().strip(), null, null, null, null)));
                odluke.merge(item.itemId(), "Neka aplikacija izabere", (a, b) -> a + "; " + b);
            } catch (RuntimeException e) {
                odluke.merge(item.itemId(), "Neka aplikacija izabere odbijeno: " + e.getMessage(), (a, b) -> a + "; " + b);
                if (item.matchingStatus() == ShoppingItemMatchingStatus.NEEDS_CONFIRMATION) {
                    matchingService.resolve(listId, item.itemId(), caller, new ResolveShoppingItemMatchRequest(
                            ShoppingItemMatchAction.REJECT, null, "Test skup: nijedan", null));
                }
            }
        }
        if (count > 0) {
            matchingService.match(listId, caller, true);
        }
        return count;
    }

    private ShoppingRecommendationResponse recommend(long listId, Lokacija lokacija) {
        return recommendationService.recommend(listId, lokacija.latitude(), lokacija.longitude(), null, TravelMode.DRIVING);
    }

    private Path write(
            TestSkup skup,
            List<Rezultat> rezultati,
            Map<String, Citanje> citanje,
            List<Izracunaj> vremena,
            Path dir,
            boolean slepo
    ) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        Files.createDirectories(dir);
        List<String> gradovi = skup.lokacije().stream().map(Lokacija::grad).toList();

        StringBuilder md = new StringBuilder();
        md.append("# Test skup ").append(skup.market()).append('-').append(skup.language())
                .append(slepo ? " (slepi deo, samo zbirno)" : "")
                .append(", ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy. HH:mm"))).append("\n\n");
        md.append("Baza: ").append(databaseVersion()).append(". Stavki: ").append(skup.stavke().size())
                .append(", spiskova: ").append(rezultati.stream().map(r -> r.grad() + "/" + r.stavka().spisak()).distinct().count())
                .append(".\n\n");

        md.append("| Grad | Stavki | Korak 1 ispravno | Prepoznato (u planu) | Ispravan izbor (od prepoznatih) | Ispravno od svih |\n")
                .append("|---|---|---|---|---|---|\n");
        for (String grad : gradovi) {
            List<Rezultat> uGradu = rezultati.stream().filter(r -> r.grad().equals(grad)).toList();
            long korak1 = uGradu.stream().filter(r -> r.prepoznavanje().ocena() == Ocena.ISPRAVNO).count();
            long prepoznato = uGradu.stream().filter(r -> r.ocena() != Ocena.NEMA).count();
            long ispravno = uGradu.stream().filter(r -> r.ocena() == Ocena.ISPRAVNO).count();
            md.append("| ").append(grad)
                    .append(" | ").append(uGradu.size())
                    .append(" | ").append(korak1).append('/').append(uGradu.size()).append(" (").append(pct(korak1, uGradu.size())).append(')')
                    .append(" | ").append(prepoznato).append('/').append(uGradu.size()).append(" (").append(pct(prepoznato, uGradu.size())).append(')')
                    .append(" | ").append(ispravno).append('/').append(prepoznato).append(" (").append(pct(ispravno, prepoznato)).append(')')
                    .append(" | ").append(pct(ispravno, uGradu.size())).append(" |\n");
        }

        List<Citanje> sKolicinom = citanje.values().stream().filter(c -> c.ocena() != null).toList();
        long citano = sKolicinom.stream().filter(c -> c.ocena() == Ocena.ISPRAVNO).count();
        md.append("\nČitanje količine (bez baze): ")
                .append(sKolicinom.isEmpty() ? "skup ne navodi količine"
                        : citano + "/" + sKolicinom.size() + " (" + pct(citano, sKolicinom.size()) + ")")
                .append(".\n\n");

        md.append("## Gde se stavka izgubi\n\nPrvi uzrok po redu: čitanje količine (parser), korak 1 (prepoznavanje), ")
                .append("pa izbor u planu i cena u blizini.\n\n| Grad |");
        GDE.forEach(g -> md.append(' ').append(g).append(" |"));
        md.append("\n|---|").append("---|".repeat(GDE.size())).append('\n');
        for (String grad : gradovi) {
            md.append("| ").append(grad).append(" |");
            for (String g : GDE) {
                md.append(' ').append(rezultati.stream()
                        .filter(r -> r.grad().equals(grad) && r.gde(citanje.get(r.stavka().id())).equals(g)).count()).append(" |");
            }
            md.append('\n');
        }

        md.append("\n## Šta je korak 1 našao\n\n| Grad | proizvod | vrsta | samo reči | ništa |\n|---|---|---|---|---|\n");
        for (String grad : gradovi) {
            md.append("| ").append(grad).append(" |");
            for (Nadjeno n : Nadjeno.values()) {
                List<Rezultat> group = rezultati.stream()
                        .filter(r -> r.grad().equals(grad) && r.prepoznavanje().nadjeno() == n).toList();
                long ok = group.stream().filter(r -> r.prepoznavanje().ocena() == Ocena.ISPRAVNO).count();
                md.append(' ').append(group.size()).append(n == Nadjeno.NISTA ? "" : " (" + ok + " ispravno)").append(" |");
            }
            md.append('\n');
        }

        md.append("\n## Izračunaj\n\n| Grad | Stavki | Ukupno | Korak 1 | Preporuka | Odluka u koraku 1 |\n|---|---|---|---|---|---|\n");
        for (Izracunaj v : vremena) {
            md.append("| ").append(v.grad()).append(" | ").append(v.stavki()).append(" | ").append(seconds(v.ukupnoMs()))
                    .append(" | ").append(seconds(v.korak1Ms())).append(" | ").append(seconds(v.preporukaMs()))
                    .append(" | ").append(v.odluka()).append(" |\n");
        }
        md.append("\nPrvi grad meri korak 1 bez keša. Vreme odluka u koraku 1 nije uračunato.\n\n");

        md.append("## Po izvoru\n\n| Izvor | Stavki | Korak 1 ispravno | Ispravno |\n|---|---|---|---|\n");
        rezultati.stream().map(r -> r.stavka().izvor()).distinct().forEach(izvor -> {
            List<Rezultat> grupa = rezultati.stream().filter(r -> r.stavka().izvor().equals(izvor)).toList();
            md.append("| ").append(izvor).append(" | ").append(grupa.size())
                    .append(" | ").append(pct(grupa.stream().filter(r -> r.prepoznavanje().ocena() == Ocena.ISPRAVNO).count(), grupa.size()))
                    .append(" | ").append(pct(grupa.stream().filter(r -> r.ocena() == Ocena.ISPRAVNO).count(), grupa.size()))
                    .append(" |\n");
        });

        String name = skup.market() + (slepo ? "-slepo-" : "-") + stamp;
        if (slepo) {
            md.append("\nSlepi deo: stavke i proizvodi se ne ispisuju, da ih nijedna nit ne vidi pre kraja.\n");
        } else {
            md.append("\n## Stavke\n\n| Id | Grad | Sa spiska | Količina | Korak 1 | Plan |\n|---|---|---|---|---|---|\n");
            StringBuilder tsv = new StringBuilder(
                    "id\tspisak\tgrad\ttekst\tkolicina_ocekivana\tprocitano\tcitanje\tkorak1\tnadjeno\tnaziv_korak1\tocena_korak1"
                            + "\tgde\tocena\tscenario\tproizvod\tlanac\tpakovanja\trazlog\n");
            for (Rezultat r : rezultati) {
                Citanje c = citanje.get(r.stavka().id());
                Prepoznavanje p = r.prepoznavanje();
                md.append("| ").append(r.stavka().id()).append(" | ").append(r.grad())
                        .append(" | ").append(cell(r.stavka().tekst()))
                        .append(" | ").append(c.ocena() == null ? "" : mark(c.ocena()) + " ").append(cell(c.procitano()))
                        .append(r.stavka().kolicina() == null || c.ocena() != Ocena.POGRESNO ? "" : " **treba " + r.stavka().kolicina() + "**")
                        .append(" | ").append(mark(p.ocena())).append(' ').append(cell(r.korak1()))
                        .append(p.naziv() == null ? "" : ": " + cell(p.naziv()))
                        .append(" | ").append(mark(r.ocena())).append(' ');
                if (r.izbori().isEmpty()) {
                    md.append(cell(Objects.requireNonNullElse(r.bezCene(), "nema u planu")));
                } else {
                    md.append(r.izbori().stream().map(i -> cell(i.proizvod()) + " (" + cell(String.valueOf(i.lanac()))
                                    + (i.pakovanja() == null ? "" : ", " + i.pakovanja().stripTrailingZeros().toPlainString() + "×")
                                    + ")" + (i.razlog() == null ? "" : " **" + i.razlog() + "**"))
                            .distinct().collect(Collectors.joining("; ")));
                }
                md.append(" |\n");

                List<String> common = List.of(
                        r.stavka().id(), Objects.toString(r.stavka().spisak(), ""), r.grad(), r.stavka().tekst(),
                        Objects.toString(r.stavka().kolicina(), ""), c.procitano(), c.ocena() == null ? "" : c.ocena().name(),
                        r.korak1(), p.nadjeno().name(), Objects.toString(p.naziv(), ""), p.ocena().name(),
                        r.gde(c), r.ocena().name());
                if (r.izbori().isEmpty()) {
                    tsv.append(tsvRow(common, List.of("", "", "", "", Objects.toString(r.bezCene(), ""))));
                } else {
                    for (Izbor i : r.izbori()) {
                        tsv.append(tsvRow(common, List.of(i.scenario(), i.proizvod(), String.valueOf(i.lanac()),
                                i.pakovanja() == null ? "" : i.pakovanja().toPlainString(), Objects.toString(i.razlog(), ""))));
                    }
                }
            }
            Files.writeString(dir.resolve("stavke-" + name + ".tsv"), tsv, StandardCharsets.UTF_8);
        }

        Path report = dir.resolve("izvestaj-" + name + ".md");
        Files.writeString(report, md, StandardCharsets.UTF_8);
        return report;
    }

    private static String tsvRow(List<String> first, List<String> rest) {
        return Stream.concat(first.stream(), rest.stream())
                .map(v -> v.replace('\t', ' ').replace('\n', ' '))
                .collect(Collectors.joining("\t")) + "\n";
    }

    private String databaseVersion() {
        Integer flyway = jdbcClient.sql("SELECT MAX(version::int) FROM app.flyway_schema_history WHERE success")
                .query(Integer.class).single();
        String prices = jdbcClient.sql("SELECT MAX(price_date)::text FROM app.price_observation")
                .query(String.class).optional().orElse("?");
        return "Flyway V" + flyway + ", najnoviji cenovnik " + prices;
    }

    private long list(DeviceCaller caller, List<Long> lists, String name) {
        long id = shoppingListService.create(new CreateShoppingListRequest(name), caller.accountId()).id();
        lists.add(id);
        return id;
    }

    private static double percent(List<Rezultat> rezultati, java.util.function.Predicate<Rezultat> counted) {
        return rezultati.isEmpty() ? 0.0 : 100.0 * rezultati.stream().filter(counted).count() / rezultati.size();
    }

    private static String status(ShoppingItemMatchingStatus status) {
        return switch (status) {
            case PENDING -> "čeka proveru";
            case AUTO_MATCHED -> "automatski";
            case NEEDS_CONFIRMATION -> "treba potvrda";
            case CONFIRMED -> "potvrđeno";
            case UNMATCHED -> "nije pronađeno";
        };
    }

    private static String mark(Ocena ocena) {
        return switch (ocena) {
            case ISPRAVNO -> "✓";
            case POGRESNO -> "✗";
            case NEMA -> "—";
        };
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "/").replace("\n", " ").replaceAll("\\s+", " ").strip();
    }

    private static String pct(long part, long whole) {
        return whole == 0 ? "–" : String.format(Locale.ROOT, "%.0f%%", 100.0 * part / whole);
    }

    private static String seconds(long millis) {
        return String.format(Locale.ROOT, "%.1f s", millis / 1000.0);
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
