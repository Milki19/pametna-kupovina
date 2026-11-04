package rs.pametnakupovina.backend.product;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogMemoTest {

    private final MovableClock clock = new MovableClock();
    private final CatalogMemo memo = new CatalogMemo(true, clock);
    private final AtomicInteger reads = new AtomicInteger();

    private String read() {
        reads.incrementAndGet();
        return "jogurt";
    }

    @Test
    void theSameWordIsReadOnce() {
        assertThat(memo.<String>remember(List.of("kind", "jogurt"), this::read)).isEqualTo("jogurt");
        assertThat(memo.<String>remember(List.of("kind", "jogurt"), this::read)).isEqualTo("jogurt");
        memo.remember(List.of("kind", "mleko"), this::read);

        assertThat(reads).hasValue(2);
    }

    @Test
    void nothingFoundIsKeptToo() {
        memo.remember(List.of("kind", "xyz"), () -> {
            reads.incrementAndGet();
            return null;
        });
        assertThat(memo.<String>remember(List.of("kind", "xyz"), this::read)).isNull();

        assertThat(reads).hasValue(1);
    }

    @Test
    void anImportOrReviewStartsOver() {
        memo.remember(List.of("kind", "jogurt"), this::read);
        memo.catalogChanged();
        memo.remember(List.of("kind", "jogurt"), this::read);

        assertThat(reads).hasValue(2);
    }

    @Test
    void anHourLaterItIsReadAgain() {
        memo.remember(List.of("kind", "jogurt"), this::read);
        clock.move(CatalogMemo.MAX_AGE.plusMinutes(1));
        memo.remember(List.of("kind", "jogurt"), this::read);

        assertThat(reads).hasValue(2);
    }

    @Test
    void whatWasReadDuringAChangeIsNotKeptPastItsCommit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            memo.catalogChanged();
            memo.remember(List.of("kind", "jogurt"), this::read);
            for (TransactionSynchronization synchronization
                    : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        memo.remember(List.of("kind", "jogurt"), this::read);

        assertThat(reads).hasValue(2);
    }

    @Test
    void aLookupOverlappingAChangeIsNotKept() {
        memo.remember(List.of("kind", "jogurt"), () -> {
            memo.catalogChanged();
            return read();
        });
        memo.remember(List.of("kind", "jogurt"), this::read);

        assertThat(reads).hasValue(2);
    }

    @Test
    void twentyAskingAtOnceReadOnce() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        List<Thread> searches = new java.util.ArrayList<>();
        for (int search = 0; search < 20; search++) {
            Thread thread = new Thread(() -> memo.rememberLarge(List.of("searchCandidates", "mleko"), () -> {
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return read();
            }));
            searches.add(thread);
            thread.start();
        }
        rs.pametnakupovina.backend.SingleFlightTestAccess.waitUntilAllWait(searches);
        release.countDown();
        for (Thread thread : searches) {
            thread.join(10_000);
        }

        assertThat(reads).hasValue(1);
    }

    @Test
    void searchesAreKeptTooButOnlyTheLastFew() {
        memo.rememberLarge(List.of("searchCandidates", "mleko"), this::read);
        memo.rememberLarge(List.of("searchCandidates", "mleko"), this::read);
        assertThat(reads).hasValue(1);

        for (int word = 0; word < CatalogMemo.MAX_LARGE_ENTRIES; word++) {
            memo.rememberLarge(List.of("searchCandidates", "rec" + word), () -> "x");
        }
        assertThat(memo.size()).isLessThanOrEqualTo(CatalogMemo.MAX_LARGE_ENTRIES);

        memo.catalogChanged();
        assertThat(memo.size()).isZero();
    }

    @Test
    void aPlansOffersAreKeptTenMinutesAndClearedByAnImportOrAReport() {
        memo.rememberOffers(List.of("planOffers", 1L), this::read);
        memo.rememberOffers(List.of("planOffers", 1L), this::read);
        assertThat(reads).hasValue(1);

        clock.move(CatalogMemo.OFFER_MAX_AGE.plusSeconds(1));
        memo.rememberOffers(List.of("planOffers", 1L), this::read);
        assertThat(reads).hasValue(2);

        memo.offersChanged();
        memo.rememberOffers(List.of("planOffers", 1L), this::read);
        assertThat(reads).hasValue(3);

        memo.catalogChanged();
        memo.rememberOffers(List.of("planOffers", 1L), this::read);
        assertThat(reads).hasValue(4);
    }

    @Test
    void aReportOnlyClearsThePlansOffers() {
        memo.remember(List.of("kind", "jogurt"), this::read);
        memo.rememberOffers(List.of("planOffers", 1L), this::read);
        memo.offersChanged();
        memo.remember(List.of("kind", "jogurt"), this::read);

        assertThat(reads).hasValue(2);
    }

    @Test
    void anItemsOffersKeptByHandLastTenMinutes() {
        List<String> key = List.of("itemOffers", "mleko 1 l");
        assertThat((Object) memo.keptOffers(key)).isNull();

        memo.keepOffers(key, "offers", memo.offersGeneration());
        assertThat((Object) memo.keptOffers(key)).isEqualTo("offers");

        clock.move(Duration.ofMinutes(11));
        assertThat((Object) memo.keptOffers(key)).isNull();
    }

    @Test
    void anItemsOffersReadDuringAReportAreNotKept() {
        List<String> key = List.of("itemOffers", "mleko 1 l");
        long before = memo.offersGeneration();
        memo.offersChanged();

        memo.keepOffers(key, "offers", before);

        assertThat((Object) memo.keptOffers(key)).isNull();
    }

    @Test
    void switchedOffItAlwaysReads() {
        CatalogMemo off = new CatalogMemo(false);
        off.remember(List.of("kind", "jogurt"), this::read);
        off.remember(List.of("kind", "jogurt"), this::read);

        assertThat(reads).hasValue(2);
    }

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-31T10:00:00Z");

        void move(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
