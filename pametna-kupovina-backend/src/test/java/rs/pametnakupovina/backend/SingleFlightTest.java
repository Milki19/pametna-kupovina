package rs.pametnakupovina.backend;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightTest {

    private final SingleFlight<String> flight = new SingleFlight<>();
    private final AtomicInteger runs = new AtomicInteger();

    @Test
    void whoeverComesWhileItRunsGetsTheSameAnswer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<Object> answers = Collections.synchronizedList(new ArrayList<>());
        List<Thread> callers = new ArrayList<>();
        for (int caller = 0; caller < 5; caller++) {
            Thread thread = new Thread(() -> answers.add(flight.run("plan", () -> {
                runs.incrementAndGet();
                awaitQuietly(release);
                return new Object();
            })));
            callers.add(thread);
            thread.start();
        }
        waitUntilAllWait(callers);
        release.countDown();
        for (Thread thread : callers) {
            thread.join(10_000);
        }

        assertThat(runs).hasValue(1);
        assertThat(answers).hasSize(5).allMatch(answer -> answer == answers.getFirst());
        assertThat(flight.running()).isZero();
    }

    @Test
    void nothingIsKeptOnceTheWorkEnds() {
        flight.run("plan", runs::incrementAndGet);
        flight.run("plan", runs::incrementAndGet);

        assertThat(runs).hasValue(2);
        assertThat(flight.running()).isZero();
    }

    @Test
    void differentWorkRunsSideBySide() {
        String answer = flight.run("a", () -> flight.run("b", () -> "b inside a"));

        assertThat(answer).isEqualTo("b inside a");
    }

    @Test
    void aFailureReachesTheCallerAndIsNotKept() {
        assertThatThrownBy(() -> flight.run("plan", () -> {
            throw new IllegalStateException("baza");
        })).isInstanceOf(IllegalStateException.class).hasMessage("baza");

        assertThat(flight.<String>run("plan", () -> "posle")).isEqualTo("posle");
        assertThat(flight.running()).isZero();
    }

    static void waitUntilAllWait(List<Thread> threads) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!threads.stream().allMatch(thread -> thread.getState() == Thread.State.WAITING)) {
            assertThat(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
