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
