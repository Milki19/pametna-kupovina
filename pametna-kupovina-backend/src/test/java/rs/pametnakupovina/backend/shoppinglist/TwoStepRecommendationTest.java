package rs.pametnakupovina.backend.shoppinglist;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Preporuke u dva koraka (šesti test, stavka 12: 100 stavki su bile 2,3 s i
 * 212 KB): every plan's totals and coverage at once, the items of the plan
 * asked for, the others when opened. Without a plan, the answer the apps
 * before 2.1 read, item for item.
 */
class TwoStepRecommendationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void withoutAPlanTheAnswerIsTheWholeOldOne() {
        ShoppingRecommendationResponse whole = answer(100);

        ShoppingRecommendationResponse same = whole.detailsOf(null);

        assertThat(same).isSameAs(whole);
        for (OptimizationScenarioResponse s : List.of(same.singleStore(), same.recommendedBalance(), same.lowestPrice())) {
            assertThat(s.items()).hasSize(100);
            assertThat(s.detailed()).isTrue();
        }
    }

    @Test
    void theAskedPlanKeepsItsItemsAndTheOthersOnlyTheirSummary() {
        ShoppingRecommendationResponse whole = answer(100);

        ShoppingRecommendationResponse firstStep = whole.detailsOf(RecommendationScenarioType.RECOMMENDED_BALANCE);

        assertThat(firstStep.recommendedBalance()).isEqualTo(whole.recommendedBalance());
        for (OptimizationScenarioResponse s : List.of(firstStep.singleStore(), firstStep.lowestPrice())) {
            assertThat(s.detailed()).isFalse();
            assertThat(s.items()).isEmpty();
        }
        OptimizationScenarioResponse single = firstStep.singleStore();
        OptimizationScenarioResponse wholeSingle = whole.singleStore();
        // Everything a tile, the comparison and "same plan twice" need stays.
        assertThat(single.itemCount()).isEqualTo(100);
        assertThat(single.coveredItems()).isEqualTo(wholeSingle.coveredItems());
        assertThat(single.totalCost()).isEqualTo(wholeSingle.totalCost());
        assertThat(single.basketCost()).isEqualTo(wholeSingle.basketCost());
        assertThat(single.routeDurationSeconds()).isEqualTo(wholeSingle.routeDurationSeconds());
        assertThat(single.stores()).isEqualTo(wholeSingle.stores());
        assertThat(single.onTheWay()).isEqualTo(wholeSingle.onTheWay());
        assertThat(single.availableItemIds()).isEqualTo(wholeSingle.availableItemIds()).hasSize(90);
    }

    @Test
    void anotherPlanOpenedLaterCarriesItsItems() {
        ShoppingRecommendationResponse whole = answer(10);

        ShoppingRecommendationResponse opened = whole.detailsOf(RecommendationScenarioType.LOWEST_PRICE);

        assertThat(opened.lowestPrice().items()).hasSize(10);
        assertThat(opened.recommendedBalance().items()).isEmpty();
        assertThat(opened.singleStore().items()).isEmpty();
    }

    @Test
    void theFirstStepIsAboutAThirdOfTheOldAnswer() {
        ShoppingRecommendationResponse whole = answer(100);

        int before = JSON.writeValueAsBytes(whole).length;
        int after = JSON.writeValueAsBytes(whole.detailsOf(RecommendationScenarioType.RECOMMENDED_BALANCE)).length;

        assertThat(after).isLessThan(before * 2 / 5);
    }

    @Test
    void theJsonKeepsEveryOldFieldAndAddsOnlyTheSummaryOnes() {
        JsonNode tile = JSON.readTree(JSON.writeValueAsBytes(
                answer(3).detailsOf(RecommendationScenarioType.RECOMMENDED_BALANCE))).get("singleStore");

        assertThat(tile.get("items").isArray()).isTrue();
        assertThat(tile.get("detailed").asBoolean()).isFalse();
        assertThat(tile.get("itemCount").asInt()).isEqualTo(3);
        assertThat(tile.has("withoutItems")).isFalse();
        assertThat(tile.has("totalCost")).isTrue();
        assertThat(tile.has("coveredItems")).isTrue();
    }

    private static ShoppingRecommendationResponse answer(int itemCount) {
        return new ShoppingRecommendationResponse(7L, "Spisak", LocalDate.of(2026, 11, 8), 12, 12, 30,
                new OptimizationAssumptions(3000, 30, 7, new BigDecimal("15.00"), new BigDecimal("400.00"),
                        new BigDecimal("50.00"), new BigDecimal("30"), "RSD", TravelMode.DRIVING),
                scenario(RecommendationScenarioType.SINGLE_STORE, itemCount, 1L),
                scenario(RecommendationScenarioType.RECOMMENDED_BALANCE, itemCount, 2L),
                scenario(RecommendationScenarioType.LOWEST_PRICE, itemCount, 3L),
                List.of(), "Cene su informativne.");
    }

    private static OptimizationScenarioResponse scenario(RecommendationScenarioType type, int itemCount, long storeId) {
        List<RecommendationItemResponse> items = new ArrayList<>();
        IntStream.range(0, itemCount).forEach(i -> items.add(new RecommendationItemResponse(
                (long) i, "stavka " + i, BigDecimal.ONE, ShoppingItemRule.FLEXIBLE_CATEGORY,
                ShoppingItemMatchingStatus.AUTO_MATCHED,
                i % 10 == 9 ? RecommendationItemStatus.NO_VALID_PRICE : RecommendationItemStatus.AVAILABLE,
                storeId, "maxi", "Maxi", 100L + i, 200L + i, "Proizvod broj " + i, "Brend", "860000000000" + i,
                LocalDate.of(2026, 11, 8), new BigDecimal("129.99"), new BigDecimal("129.99"), "STORE",
                "Najniža važeća cena u ovoj prodavnici.")));
        RecommendationStoreResponse store = new RecommendationStoreResponse(1, storeId, "maxi", "Maxi",
                "SUPER", "Supermarket", "Maxi Centar", "Knez Mihailova 1", "Beograd", 44.81, 20.46, 1.2, 240);
        return new OptimizationScenarioResponse(type, true, false, "Plan.", itemCount - itemCount / 10, 0,
                itemCount / 10, 1, new BigDecimal("12999.00"), 2.4, 480, new BigDecimal("36.00"),
                new BigDecimal("53.33"), new BigDecimal("50.00"), new BigDecimal("13138.33"), BigDecimal.ZERO,
                "OSRM", "ROAD", false, List.of("Maxi"), LocalDate.of(2026, 11, 8), List.of(store), items,
                null, null);
    }
}
