package rs.pametnakupovina.backend.retailerlocation;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.retailerlocation.dis.DisLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.lidl.LidlLocationImportService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerifiedLocationImportSchedulerTest {

    @Test
    void continuesWithNextRetailerWhenOneOfficialSourceFails() {
        DisLocationImportService disService = mock(
                DisLocationImportService.class
        );
        LidlLocationImportService lidlService = mock(
                LidlLocationImportService.class
        );
        when(disService.importLatest()).thenThrow(
                new IllegalStateException("DIS privremeno nije dostupan")
        );
        when(lidlService.importLatest()).thenReturn(
                new RetailerLocationImportResult(
                        "LIDL",
                        86,
                        86,
                        0,
                        "SUCCEEDED"
                )
        );

        VerifiedLocationImportScheduler scheduler =
                new VerifiedLocationImportScheduler(
                        disService,
                        lidlService
                );

        scheduler.importVerifiedLocations();

        verify(disService).importLatest();
        verify(lidlService).importLatest();
    }
}
