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
 * Pastes the test set into a throwaway list, runs step 1 and the plan for
 * every city the way the web app does, grades what the plan picked and
 * writes the report. The list and the made-up phone are erased afterwards.
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

    /** Percentages per city, and where the report went. */
    public record Ishod(Path izvestaj, Map<String, Double> prepoznato, Map<String, Double> ispravno) {
    }

    public Ishod meri(TestSkup skup, Path dir) throws IOException {
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
            long kratak = list(caller, lists, "Test skup " + IZRACUNAJ_STAVKI);
            shoppingListService.addPastedItems(kratak, caller.accountId(), new PasteShoppingListItemsRequest(
                    skup.stavke().stream().limit(IZRACUNAJ_STAVKI).map(Stavka::tekst).collect(Collectors.joining("\n"))));
            List<Izracunaj> vremena = new ArrayList<>();
            for (Lokacija lokacija : skup.lokacije()) {
                long t0 = System.nanoTime();
                ShoppingListMatchingResponse matching = matchingService.match(kratak, caller, true);
                long t1 = System.nanoTime();
                int odluka = appChooses(kratak, caller, matching, new LinkedHashMap<>());
                long t2 = System.nanoTime();
                recommend(kratak, lokacija);
                long t3 = System.nanoTime();
                vremena.add(new Izracunaj(lokacija.grad(), ms(t1 - t0), ms(t3 - t2), odluka));
            }

            List<Rezultat> rezultati = measure(skup, caller, list(caller, lists, "Test skup"));
            Map<String, Double> prepoznato = new LinkedHashMap<>();
            Map<String, Double> ispravno = new LinkedHashMap<>();
            for (Lokacija lokacija : skup.lokacije()) {
                prepoznato.put(lokacija.grad(), percent(rezultati, lokacija.grad(), false));
                ispravno.put(lokacija.grad(), percent(rezultati, lokacija.grad(), true));
            }
            return new Ishod(write(skup, rezultati, vremena, dir), prepoznato, ispravno);
        } finally {
            for (long list : lists) {
                shoppingListService.deleteList(list, caller.accountId());
            }
            accountRepository.forget(caller.clientTokenHash());
        }
    }

    private record Izracunaj(String grad, long korak1Ms, long preporukaMs, int odluka) {
        long ukupnoMs() {
            return korak1Ms + preporukaMs;
        }
    }

    private record Izbor(String scenario, String proizvod, String lanac, BigDecimal pakovanja, Ocena ocena, String razlog) {
    }

    private record Rezultat(Stavka stavka, String korak1, Map<String, List<Izbor>> poGradu, Map<String, String> bezCene) {

        Ocena ocena(String grad) {
            List<Izbor> izbori = poGradu.getOrDefault(grad, List.of());
            if (izbori.isEmpty()) {
                return Ocena.NEMA;
            }
            return izbori.stream().allMatch(i -> i.ocena() == Ocena.ISPRAVNO) ? Ocena.ISPRAVNO : Ocena.POGRESNO;
        }
    }

    /** Each line is pasted on its own, so a line the parser rejects costs only that line. */
    private List<Rezultat> measure(TestSkup skup, DeviceCaller caller, long listId) {
        Map<Long, Stavka> stavkaByItem = new LinkedHashMap<>();
        Map<String, String> korak1 = new LinkedHashMap<>();
        for (Stavka stavka : skup.stavke()) {
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

        Map<String, Map<Long, List<Izbor>>> izbori = new LinkedHashMap<>();
        Map<String, Map<Long, String>> bezCene = new LinkedHashMap<>();
        for (Lokacija lokacija : skup.lokacije()) {
            ShoppingRecommendationResponse plan = recommend(listId, lokacija);
            Map<Long, List<Izbor>> grad = new LinkedHashMap<>();
            Map<Long, String> razlozi = new LinkedHashMap<>();
            Stream.of(plan.singleStore(), plan.recommendedBalance(), plan.lowestPrice())
                    .filter(Objects::nonNull)
                    .forEach(scenario -> collect(scenario, stavkaByItem, grad, razlozi));
            izbori.put(lokacija.grad(), grad);
            bezCene.put(lokacija.grad(), razlozi);
        }

        List<Rezultat> rezultati = new ArrayList<>();
        Map<String, Long> itemByStavka = stavkaByItem.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getValue().id(), Map.Entry::getKey));
        for (Stavka stavka : skup.stavke()) {
            Long itemId = itemByStavka.get(stavka.id());
            Map<String, List<Izbor>> poGradu = new LinkedHashMap<>();
            Map<String, String> razlog = new LinkedHashMap<>();
            for (Lokacija lokacija : skup.lokacije()) {
                poGradu.put(lokacija.grad(), itemId == null ? List.of()
                        : izbori.get(lokacija.grad()).getOrDefault(itemId, List.of()));
                if (itemId != null && bezCene.get(lokacija.grad()).containsKey(itemId)) {
                    razlog.put(lokacija.grad(), bezCene.get(lokacija.grad()).get(itemId));
                }
            }
            rezultati.add(new Rezultat(stavka, korak1.getOrDefault(stavka.id(), "?"), poGradu, razlog));
        }
        return rezultati;
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

    private Path write(TestSkup skup, List<Rezultat> rezultati, List<Izracunaj> vremena, Path dir) throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        Files.createDirectories(dir);
        List<String> gradovi = skup.lokacije().stream().map(Lokacija::grad).toList();

        StringBuilder md = new StringBuilder();
        md.append("# Test skup ").append(skup.market()).append('-').append(skup.language())
                .append(", ").append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy. HH:mm"))).append("\n\n");
        md.append("Baza: ").append(databaseVersion()).append(". Stavki: ").append(rezultati.size()).append(".\n\n");
        md.append("| Grad | Prepoznato | Ispravan izbor (od prepoznatih) | Ispravno od svih | Izračunaj, ")
                .append(IZRACUNAJ_STAVKI).append(" stavki |\n|---|---|---|---|---|\n");
        for (String grad : gradovi) {
            long prepoznato = rezultati.stream().filter(r -> r.ocena(grad) != Ocena.NEMA).count();
            long ispravno = rezultati.stream().filter(r -> r.ocena(grad) == Ocena.ISPRAVNO).count();
            Izracunaj v = vremena.stream().filter(t -> t.grad().equals(grad)).findFirst().orElseThrow();
            md.append("| ").append(grad)
                    .append(" | ").append(prepoznato).append('/').append(rezultati.size()).append(" (").append(pct(prepoznato, rezultati.size())).append(')')
                    .append(" | ").append(ispravno).append('/').append(prepoznato).append(" (").append(pct(ispravno, prepoznato)).append(')')
                    .append(" | ").append(pct(ispravno, rezultati.size()))
                    .append(" | ").append(seconds(v.ukupnoMs())).append(" (korak 1 ").append(seconds(v.korak1Ms()))
                    .append(", preporuka ").append(seconds(v.preporukaMs())).append(", odluka u koraku 1: ").append(v.odluka()).append(") |\n");
        }
        md.append("\nPrvi grad meri korak 1 bez keša; drugi grad ponovo pokreće korak 1 nad istim spiskom. ")
                .append("Vreme odluka u koraku 1 nije uračunato.\n\n");

        md.append("## Po izvoru\n\n| Izvor |");
        gradovi.forEach(g -> md.append(' ').append(g).append(" ispravno |"));
        md.append("\n|---|").append("---|".repeat(gradovi.size())).append('\n');
        rezultati.stream().map(r -> r.stavka().izvor()).distinct().forEach(izvor -> {
            List<Rezultat> grupa = rezultati.stream().filter(r -> r.stavka().izvor().equals(izvor)).toList();
            md.append("| ").append(izvor).append(" |");
            gradovi.forEach(g -> md.append(' ')
                    .append(grupa.stream().filter(r -> r.ocena(g) == Ocena.ISPRAVNO).count()).append('/').append(grupa.size()).append(" |"));
            md.append('\n');
        });

        md.append("\n## Stavke\n\n| Id | Sa spiska | Korak 1 |");
        gradovi.forEach(g -> md.append(' ').append(g).append(" |"));
        md.append("\n|---|---|---|").append("---|".repeat(gradovi.size())).append('\n');
        StringBuilder tsv = new StringBuilder("id\ttekst\tkorak1\tgrad\tocena\tscenario\tproizvod\tlanac\tpakovanja\trazlog\n");
        for (Rezultat r : rezultati) {
            md.append("| ").append(r.stavka().id()).append(" | ").append(cell(r.stavka().tekst())).append(" | ").append(cell(r.korak1())).append(" |");
            for (String grad : gradovi) {
                List<Izbor> izbori = r.poGradu().get(grad);
                md.append(' ').append(mark(r.ocena(grad))).append(' ');
                if (izbori.isEmpty()) {
                    md.append(cell(r.bezCene().getOrDefault(grad, "nema u planu")));
                    tsv.append(String.join("\t", r.stavka().id(), r.stavka().tekst(), r.korak1(), grad, Ocena.NEMA.name(),
                            "", "", "", "", r.bezCene().getOrDefault(grad, ""))).append('\n');
                } else {
                    md.append(izbori.stream().map(i -> cell(i.proizvod()) + " (" + cell(String.valueOf(i.lanac()))
                                    + (i.pakovanja() == null ? "" : ", " + i.pakovanja().stripTrailingZeros().toPlainString() + "×")
                                    + ")" + (i.razlog() == null ? "" : " **" + i.razlog() + "**"))
                            .distinct().collect(Collectors.joining("; ")));
                    for (Izbor i : izbori) {
                        tsv.append(String.join("\t", r.stavka().id(), r.stavka().tekst(), r.korak1(), grad, i.ocena().name(),
                                i.scenario(), i.proizvod(), String.valueOf(i.lanac()),
                                i.pakovanja() == null ? "" : i.pakovanja().toPlainString(),
                                i.razlog() == null ? "" : i.razlog())).append('\n');
                    }
                }
                md.append(" |");
            }
            md.append('\n');
        }

        String name = skup.market() + "-" + stamp;
        Files.writeString(dir.resolve("stavke-" + name + ".tsv"), tsv, StandardCharsets.UTF_8);
        Path report = dir.resolve("izvestaj-" + name + ".md");
        Files.writeString(report, md, StandardCharsets.UTF_8);
        return report;
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

    private static double percent(List<Rezultat> rezultati, String grad, boolean ispravno) {
        long count = rezultati.stream()
                .filter(r -> ispravno ? r.ocena(grad) == Ocena.ISPRAVNO : r.ocena(grad) != Ocena.NEMA)
                .count();
        return 100.0 * count / rezultati.size();
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
