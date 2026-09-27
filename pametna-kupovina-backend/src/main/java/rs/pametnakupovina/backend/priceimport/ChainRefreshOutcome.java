package rs.pametnakupovina.backend.priceimport;

import java.time.LocalDate;

/**
 * How one chain's step of a daily cycle ended.
 *
 * @param status SUCCEEDED, WARNING or FAILED
 */
public record ChainRefreshOutcome(LocalDate snapshotDate, int rows, String status, String detail) {

    public static String classify(String status, LocalDate snapshot, LocalDate today) {
        if (status.equals("FAILED")) return "FAILED";
        return status.equals("SUCCEEDED") && snapshot != null
                && !snapshot.isBefore(today.minusDays(1)) && !snapshot.isAfter(today) ? "SUCCEEDED" : "WARNING";
    }

    /** A chain-wide list is as fresh as the chain publishes it. */
    public static String classifyChainWide(String importStatus) {
        if (importStatus.equals("FAILED")) return "FAILED";
        return importStatus.equals("SUCCEEDED") ? "SUCCEEDED" : "WARNING";
    }

    public static boolean failsTheDay(boolean required, String status) {
        return required && status.equals("FAILED");
    }

    public static boolean warnsTheDay(boolean required, String status) {
        return status.equals("WARNING") || (!required && status.equals("FAILED"));
    }
}
