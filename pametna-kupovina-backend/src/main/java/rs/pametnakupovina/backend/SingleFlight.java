package rs.pametnakupovina.backend;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The same work asked for by several callers at once is done once: whoever
 * comes while it runs waits for that answer instead of starting their own.
 * Nothing is kept after the work ends, so a key (a plan's location too)
 * lives only as long as the request that brought it.
 *
 * <p>Five people opening the same plan at once were answered after 1, 2, 3,
 * 4 and 5 s on the server's two cores (peti test, 1.11.2026).
 */
public final class SingleFlight<K> {

    private final Map<K, CompletableFuture<Object>> running = new ConcurrentHashMap<>();

    /** The work's answer, or the answer of the same work already running. */
    @SuppressWarnings("unchecked")
    public <T> T run(K key, Supplier<T> work) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> theirs = running.putIfAbsent(key, mine);
        if (theirs != null) {
            return (T) await(theirs);
        }
        try {
            T answer = work.get();
            mine.complete(answer);
            return answer;
        } catch (RuntimeException | Error failure) {
            mine.completeExceptionally(failure);
            throw failure;
        } finally {
            running.remove(key, mine);
        }
    }

    /** How many different pieces of work are running now. */
    public int running() {
        return running.size();
    }

    private static Object await(CompletableFuture<Object> answer) {
        try {
            return answer.join();
        } catch (CompletionException wrapped) {
            // The caller sees what the work threw, as if it had run it.
            if (wrapped.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            if (wrapped.getCause() instanceof Error failure) {
                throw failure;
            }
            throw wrapped;
        }
    }
}
