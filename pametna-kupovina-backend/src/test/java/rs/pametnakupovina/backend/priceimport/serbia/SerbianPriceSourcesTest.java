package rs.pametnakupovina.backend.priceimport.serbia;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import rs.pametnakupovina.backend.priceimport.ChainPriceSource;
import rs.pametnakupovina.backend.priceimport.ImportResult;
import rs.pametnakupovina.backend.priceimport.PriceImportService;
import rs.pametnakupovina.backend.priceimport.maxi.MaxiPriceImportCoordinator;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SerbianPriceSourcesTest {

    private final PriceImportService imports = mock(PriceImportService.class);
    private final SerbianPriceSources sources = spy(new SerbianPriceSources(
            imports, mock(MaxiPriceImportCoordinator.class), mock(JdbcClient.class)));

    @Test
    void coreChainsAreRequiredAndRunBeforeTheChainWideLists() {
        doReturn(List.of("NEW_CHAIN")).when(sources).registeredChains();

        List<ChainPriceSource> chains = sources.chains();

        assertThat(sources.marketCode()).isEqualTo("RS");
        assertThat(chains).extracting(ChainPriceSource::code).containsExactly(
                "LIDL", "EUROPROM", "IDEA_RODA", "UNIVEREXPORT", "MAXI",
                "MAXI_CATALOG", "METRO", "VEROPOULOS", "NEW_CHAIN");
        assertThat(chains).extracting(ChainPriceSource::required).containsExactly(
                true, true, true, true, true, false, false, false, false);
    }

    @Test
    void delhaizeCatalogueImportsMaxisChainWideList() {
        doReturn(List.of()).when(sources).registeredChains();
        LocalDate today = LocalDate.of(2026, 9, 27);
        when(imports.importPrices("MAXI")).thenReturn(
                new ImportResult(1L, today.minusDays(5), 2, 2, 2, 0, "SUCCEEDED"));

        var outcome = sources.chains().stream()
                .filter(chain -> chain.code().equals("MAXI_CATALOG"))
                .findFirst().orElseThrow()
                .refresh(today);

        verify(imports).importPrices("MAXI");
        // A chain-wide list from five days ago is as fresh as Delhaize makes it.
        assertThat(outcome.status()).isEqualTo("SUCCEEDED");
        assertThat(outcome.detail()).isEqualTo("SUCCEEDED; cenovnik od 2026-09-22");
    }
}
