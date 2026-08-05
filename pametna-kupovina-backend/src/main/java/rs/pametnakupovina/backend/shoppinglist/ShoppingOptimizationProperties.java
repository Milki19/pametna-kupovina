package rs.pametnakupovina.backend.shoppinglist;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
@ConfigurationProperties(prefix = "shopping.optimization")
public class ShoppingOptimizationProperties {

    private int candidateRadiusMeters = 15_000;
    private int maxCandidateStores = 20;
    private BigDecimal costPerKm = new BigDecimal("20.00");
    private BigDecimal valuePerHour = new BigDecimal("400.00");
    private BigDecimal costPerStop = new BigDecimal("80.00");
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

    public BigDecimal getCostPerKm() {
        return costPerKm;
    }

    public void setCostPerKm(BigDecimal costPerKm) {
        this.costPerKm = costPerKm;
    }

    public BigDecimal getValuePerHour() {
        return valuePerHour;
    }

    public void setValuePerHour(BigDecimal valuePerHour) {
        this.valuePerHour = valuePerHour;
    }

    public BigDecimal getCostPerStop() {
        return costPerStop;
    }

    public void setCostPerStop(BigDecimal costPerStop) {
        this.costPerStop = costPerStop;
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
