package rs.pametnakupovina.backend.market;

import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A job set for three in the morning runs at three where its chains are,
 * whatever zone the server keeps. Chains in markets with different clocks
 * get one task per zone, each with only its own chains.
 */
public final class MarketSchedule {

    private MarketSchedule() {
    }

    public static void addCronTasks(
            ScheduledTaskRegistrar registrar,
            MarketRepository markets,
            String cron,
            List<String> retailerCodes,
            Consumer<List<String>> job
    ) {
        Map<ZoneId, List<String>> codesByZone = new LinkedHashMap<>();

        for (String code : retailerCodes) {
            codesByZone
                    .computeIfAbsent(
                            markets.forRetailerCode(code).timeZone(),
                            zone -> new ArrayList<>()
                    )
                    .add(code);
        }

        codesByZone.forEach((zone, codes) -> registrar.addCronTask(new CronTask(
                () -> job.accept(List.copyOf(codes)),
                new CronTrigger(cron, zone)
        )));
    }
}
