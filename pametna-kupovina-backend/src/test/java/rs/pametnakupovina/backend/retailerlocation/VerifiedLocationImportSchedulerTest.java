package rs.pametnakupovina.backend.retailerlocation;

import org.junit.jupiter.api.Test;
import rs.pametnakupovina.backend.retailerlocation.dis.DisLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.europrom.EuropromLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.idearoda.IdeaRodaLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.lidl.LidlLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.maxi.MaxiLocationImportService;
import rs.pametnakupovina.backend.retailerlocation.univerexport.UniverexportLocationImportService;

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
        EuropromLocationImportService europromService = mock(
                EuropromLocationImportService.class
        );
        MaxiLocationImportService maxiService = mock(
                MaxiLocationImportService.class
        );
        IdeaRodaLocationImportService ideaRodaService = mock(
                IdeaRodaLocationImportService.class
        );
        UniverexportLocationImportService univerexportService = mock(
                UniverexportLocationImportService.class
        );
        when(disService.importLatest()).thenThrow(
                new IllegalStateException("DIS privremeno nije dostupan")
        );
        when(lidlService.importLatest()).thenReturn(
                success("LIDL")
        );
        when(europromService.importLatest()).thenReturn(success("EUROPROM"));
        when(maxiService.importLatest()).thenReturn(success("MAXI"));
        when(ideaRodaService.importLatest()).thenReturn(success("IDEA_RODA"));
        when(univerexportService.importLatest())
                .thenReturn(success("UNIVEREXPORT"));

        VerifiedLocationImportScheduler scheduler =
                new VerifiedLocationImportScheduler(
                        disService,
                        europromService,
                        lidlService,
                        maxiService,
                        ideaRodaService,
                        univerexportService
                );

        scheduler.importVerifiedLocations();

        verify(disService).importLatest();
        verify(europromService).importLatest();
        verify(lidlService).importLatest();
        verify(maxiService).importLatest();
        verify(ideaRodaService).importLatest();
        verify(univerexportService).importLatest();
    }

    private RetailerLocationImportResult success(String retailerCode) {
        return new RetailerLocationImportResult(
                retailerCode,
                1,
                1,
                0,
                "SUCCEEDED"
        );
    }
}
