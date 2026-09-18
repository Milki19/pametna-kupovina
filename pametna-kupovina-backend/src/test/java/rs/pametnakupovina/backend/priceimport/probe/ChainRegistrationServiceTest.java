package rs.pametnakupovina.backend.priceimport.probe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Every chain needs a code in the shape the catalogue already uses. */
class ChainRegistrationServiceTest {

    @Test
    void companyFormsAndAccentsAreDroppedFromTheCode() {
        assertThat(ChainRegistrationService.retailerCode("Aman d.o.o."))
                .isEqualTo("AMAN");
        assertThat(ChainRegistrationService.retailerCode("Gomex DOO"))
                .isEqualTo("GOMEX");
        assertThat(ChainRegistrationService.retailerCode("Šumadija market d.o.o."))
                .isEqualTo("SUMADIJA_MARKET");
    }

    @Test
    void cyrillicNamesGetTheSameCodeAsTheirLatinSpelling() {
        assertThat(ChainRegistrationService.retailerCode("Луки комерц Д.О.О."))
                .isEqualTo("LUKI_KOMERC");
        assertThat(ChainRegistrationService.retailerCode("Микромаркет НС доо"))
                .isEqualTo("MIKROMARKET_NS");
    }

    @Test
    void aLongCompanyNameStillFitsTheColumn() {
        String code = ChainRegistrationService.retailerCode(
                "PRIVREDNO DRUŠTVO ZA TRGOVINU, POSREDOVANJE I EXPORT-IMPORT"
        );

        assertThat(code).hasSizeLessThanOrEqualTo(30);
        assertThat(code).doesNotEndWith("_");
        assertThat(code).startsWith("PRIVREDNO_DRUSTVO");
    }
}
