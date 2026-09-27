package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * What the search for shops needs regardless of the country. What a
 * kilometre, an hour and a stop cost is in a currency, so it is the market's
 * (app.market).
 */
@Component
@ConfigurationProperties(prefix = "shopping.optimization")
public class ShoppingOptimizationProperties {

    private int candidateRadiusMeters = 15_000;
    private int maxCandidateStores = 20;
    private int maxPriceAgeDays = 30;
    private BigDecimal straightLineAverageSpeedKmh =
            new BigDecimal("30.00");

    public int getCandidateRadiusMeters() {
        return candidateRadiusMeters;
    }

    public void setCandidateRadiusMeters(int candidateRadiusMeters) {
        this.candidateRadiusMeters = candidateRadiusMeters;
    }

    public int getMaxCandidateStores() {
        return maxCandidateStores;
    }

    public void setMaxCandidateStores(int maxCandidateStores) {
        this.maxCandidateStores = maxCandidateStores;
    }

    public int getMaxPriceAgeDays() {
        return maxPriceAgeDays;
    }

    public void setMaxPriceAgeDays(int maxPriceAgeDays) {
        this.maxPriceAgeDays = maxPriceAgeDays;
    }

    public BigDecimal getStraightLineAverageSpeedKmh() {
        return straightLineAverageSpeedKmh;
    }

    public void setStraightLineAverageSpeedKmh(
            BigDecimal straightLineAverageSpeedKmh
    ) {
        this.straightLineAverageSpeedKmh =
                straightLineAverageSpeedKmh;
    }
}
