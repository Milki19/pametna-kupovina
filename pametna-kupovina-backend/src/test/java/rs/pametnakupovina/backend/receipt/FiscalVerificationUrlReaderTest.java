package rs.pametnakupovina.backend.receipt;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pravi račun (12.10.2022, „Milojko 22"): na papiru piše
 * „ПФР број рачуна: LUEDV8LB-Dt1Ov1o0-308", „Укупан износ: 3.060,00" i
 * „ПФР време: 12.10.2022. 20:47:53". Sve to stoji u samom QR kodu, pa se
 * račun može zavesti pre nego što se Poreska uprava išta pita.
 */
class FiscalVerificationUrlReaderTest {

    private static final String SAMPLE_PAYLOAD =
            "A0xVRURWOExCRHQxT3YxbzA0AQAANAEAAEDr0gEAAAAAAAABg82GSNIAAApNaWxvamtvIDIy";

    private final FiscalVerificationUrlReader reader =
            new FiscalVerificationUrlReader("suf.purs.gov.rs,simba.test.taxcore.dti.rs");

    @Test
    void theCodeOnTheReceiptCarriesTheReceipt() {
        FiscalReceiptStamp stamp = reader.read(
                "https://suf.purs.gov.rs/v/?vl=" + SAMPLE_PAYLOAD
        );

        assertThat(stamp.invoiceNumber()).isEqualTo("LUEDV8LB-Dt1Ov1o0-308");
        assertThat(stamp.totalCounter()).isEqualTo(308L);
        assertThat(stamp.totalAmount()).isEqualByComparingTo(new BigDecimal("3060.00"));
        assertThat(stamp.issuedAt())
                .isEqualTo(Instant.parse("2022-10-12T18:47:53.298Z"));
        assertThat(stamp.shopName()).isEqualTo("Milojko 22");
    }

    @Test
    void aCodeWrappedForTheAddressBarReadsTheSame() {
        FiscalReceiptStamp stamp = reader.read(
                "https://suf.purs.gov.rs/v/?vl="
                        + SAMPLE_PAYLOAD.replace("+", "%2B").replace("/", "%2F")
                        + "&x=1"
        );

        assertThat(stamp.invoiceNumber()).isEqualTo("LUEDV8LB-Dt1Ov1o0-308");
    }

    /**
     * Telefon šalje ono što je pročitao sa papira, a papir može da napiše
     * bilo šta: adresa koja nije Poreska uprava ne sme da se ni dodirne.
     */
    @Test
    void anAddressThatIsNotTheTaxOfficeIsRefused() {
        assertThatThrownBy(() -> reader.read(
                "https://zlonamerni.example/v/?vl=" + SAMPLE_PAYLOAD
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nije adresa Poreske uprave");

        assertThatThrownBy(() -> reader.read(
                "http://suf.purs.gov.rs/v/?vl=" + SAMPLE_PAYLOAD
        ))
                .hasMessageContaining("nije adresa Poreske uprave");
    }

    @Test
    void anAddressWithoutACodeIsRefused() {
        assertThatThrownBy(() -> reader.read("https://suf.purs.gov.rs/v/"))
                .hasMessageContaining("nema koda računa");
    }

    @Test
    void aCodeThatIsNotAReceiptIsRefused() {
        assertThatThrownBy(() -> reader.read(
                "https://suf.purs.gov.rs/v/?vl=bmlqZSByYWN1bg=="
        ))
                .hasMessageContaining("prekratak");

        // Verzija 1 je nešto drugo, ne ovaj format.
        assertThatThrownBy(() -> reader.read(
                "https://suf.purs.gov.rs/v/?vl="
                        + java.util.Base64.getEncoder().encodeToString(new byte[64])
        ))
                .hasMessageContaining("Nepoznata verzija");
    }

    @Test
    void aTruncatedShopNameIsLeftEmptyInsteadOfGuessed() {
        byte[] payload = java.util.Base64.getDecoder().decode(
                SAMPLE_PAYLOAD + "=".repeat((4 - SAMPLE_PAYLOAD.length() % 4) % 4)
        );
        byte[] cut = java.util.Arrays.copyOf(payload, 45);
        cut[43] = 100; // tvrdi da ime ima 100 znakova, a nema

        FiscalReceiptStamp stamp = reader.read(
                "https://suf.purs.gov.rs/v/?vl="
                        + java.util.Base64.getEncoder().encodeToString(cut)
        );

        assertThat(stamp.shopName()).isNull();
        assertThat(stamp.invoiceNumber()).isEqualTo("LUEDV8LB-Dt1Ov1o0-308");
    }
}
