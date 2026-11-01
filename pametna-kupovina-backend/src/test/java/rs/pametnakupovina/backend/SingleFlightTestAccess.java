package rs.pametnakupovina.backend;

import java.util.List;

/** For tests elsewhere that hold work and wait for everyone to queue on it. */
public final class SingleFlightTestAccess {

    private SingleFlightTestAccess() {
    }

    public static void waitUntilAllWait(List<Thread> threads) throws InterruptedException {
        SingleFlightTest.waitUntilAllWait(threads);
    }
}
