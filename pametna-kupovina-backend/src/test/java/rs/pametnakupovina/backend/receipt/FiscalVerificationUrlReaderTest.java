package rs.pametnakupovina.backend.receipt;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pravi račun (12.10.2022): na papiru piše
 * „ПФР број рачуна: LUEDV8LB-Dt1Ov1o0-308", „Укупан износ: 3.060,00" i
 * „ПФР време: 12.10.2022. 20:47:53". Sve to stoji u samom QR kodu, pa se
 * račun može zavesti pre nego što se Poreska uprava išta pita. „Milojko 22"
 * u tom kodu je oznaka kupca, ne prodavnica, i ne čita se.
 */
class FiscalVerificationUrlReaderTest {

    /** Račun iz restorana (03.01.2023), kodiran kako ga štampa kasa. */
    private static final String REAL_PAYLOAD_ENCODED =
            "A1ZCTUhYOVNYVzZVQlBaTzCyKwEA%2FmgAAMDh5AAAAAAAAAABhXchESoAAAAP3tYiO2%2BdI6Z5y2v4eC5wJTxirHDeiB1hqaKpgb%2FGvUy6yLkMNgZNqKxLqR40mK2cAfqZmKQ3%2BuCcTbec%2BQ3%2F9YY5EhTDP5HxDNhG%2FugU849FmvrVzP0sKecosSNL10dFtlH8Wgor2A2DDs8sHlmfmpokJnVcm24b%2BCz2bSCSl3HtzGRJ1w4Sw9hhdzsQ4WuPo%2FMEGMlmV8a%2Ffc7X05cWsDCHZoA5uPNWfN%2Bre8%2By5JETDJgRwNDFipYIdh0k62TMp5P0%2FzbCueIJJjas5IxAS9iIdpoTAIIl3eKwUZUWvEwtbGz5nkz52hw5%2Bmg50Uczx1SRifYq%2FEDt79xNkcceS0llpMyNdQ12TSYyL0UjMNymgGX4WPajSzPkQuFBcGLB%2BNLOn2AKLPJXa3B8b87eESXrcIbilNXS3zyr3eg4DIqcTVLXwHwcSh1WDmWKI2TFSu%2Bc6iORB11ln1kYbsEsuCoUegxRJR3RW4%2BkQz45%2Bbm4O5qWTCkDlZ73XHATWPn%2BpPfHP2Fh0Y0QK8gGxNiqrdbob3u0l8uaxKcEDaX%2F4HXnhMezvLEEwBNgWXDMn29uWYx9SWEvPrxV%2FLsIULQbE%2FlcvPeYIla63NhCyuEuGLIlwB2p%2B9O8x7sxD53fTMC7EKKRFUV13WBJS2N5%2BLUh33joYo8Qrc%2BNV2CqrtChYTftFukoKbQvCUKOYYIW0%2FA%3D";

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
    }

    /**
     * Isti račun, jednom kodiran (%2B) i jednom sa sirovim „+", kako ga neke
     * kase štampaju: „+" nije razmak, pa se oba čitaju isto.
     */
    @Test
    void aPlusInTheCodeIsAPlusNotASpace() {
        String raw = java.net.URLDecoder.decode(
                REAL_PAYLOAD_ENCODED,
                java.nio.charset.StandardCharsets.UTF_8
        );
        assertThat(raw).contains("+");

        for (String payload : new String[]{REAL_PAYLOAD_ENCODED, raw}) {
            FiscalReceiptStamp stamp = reader.read("https://suf.purs.gov.rs/v/?vl=" + payload);

            assertThat(stamp.invoiceNumber()).isEqualTo("VBMHX9SX-W6UBPZO0-76722");
            assertThat(stamp.totalAmount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        }
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

    /** Povraćaj, predračun, kopija i obuka kase nisu kupovina. */
    @Test
    void onlyAPurchaseCountsAsSpending() {
        byte[] payload = java.util.Base64.getDecoder().decode(
                SAMPLE_PAYLOAD + "=".repeat((4 - SAMPLE_PAYLOAD.length() % 4) % 4)
        );

        java.util.function.BiFunction<Integer, Integer, String> read = (invoiceType, transactionType) -> {
            byte[] changed = payload.clone();
            changed[41] = invoiceType.byteValue();
            changed[42] = transactionType.byteValue();
            return "https://suf.purs.gov.rs/v/?vl="
                    + java.util.Base64.getEncoder().encodeToString(changed);
        };

        assertThatThrownBy(() -> reader.read(read.apply(0, 1))).hasMessageContaining("povraćaj");
        assertThatThrownBy(() -> reader.read(read.apply(1, 0))).hasMessageContaining("predračun");
        assertThatThrownBy(() -> reader.read(read.apply(2, 0))).hasMessageContaining("kopija");
        assertThatThrownBy(() -> reader.read(read.apply(3, 0))).hasMessageContaining("obuku");
        // Avans je plaćen, pa ulazi.
        assertThat(reader.read(read.apply(4, 0)).totalAmount())
                .isEqualByComparingTo(new BigDecimal("3060.00"));
    }
}
