package rs.pametnakupovina.backend.storepricing;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdeaRodaPriceFormatMappingClientTest {

    @Test
    void parsesOfficialStoreAssignmentsAndAllKnownFormats() throws Exception {
        byte[] workbook = workbook(
                new String[][]{
                        {"NAPOMENA", "Pregled cenovnika"},
                        {"NAZIV OBJEKTA", "CENOVNIK"},
                        {"MP405 VALJEVO 1", "Iplus"},
                        {"MP407 RODA VALJEVO", "Rplus"},
                        {"MP101 IDEA TEST", "I0"},
                        {"MP102 IDEA TEST", "Iminus"},
                        {"MP408 RODA TEST", "Rbase"}
                }
        );

        IdeaRodaPriceFormatMappingClient client =
                new IdeaRodaPriceFormatMappingClient(
                        null,
                        null,
                        "https://data.gov.rs/sr/datasets/test/",
                        java.time.Duration.ofSeconds(1),
                        1_000_000
                );

        assertThat(client.parseWorkbook(workbook))
                .containsExactly(
                        new OfficialStorePriceFormat(
                                "MP405",
                                "VALJEVO 1",
                                "Iplus"
                        ),
                        new OfficialStorePriceFormat(
                                "MP407",
                                "RODA VALJEVO",
                                "Rplus"
                        ),
                        new OfficialStorePriceFormat(
                                "MP101",
                                "IDEA TEST",
                                "I0"
                        ),
                        new OfficialStorePriceFormat(
                                "MP102",
                                "IDEA TEST",
                                "Iminus"
                        ),
                        new OfficialStorePriceFormat(
                                "MP408",
                                "RODA TEST",
                                "Rbase"
                        )
                );
    }

    @Test
    void rejectsUnknownPriceFormat() throws Exception {
        byte[] workbook = workbook(new String[][]{
                {"NAZIV OBJEKTA", "CENOVNIK"},
                {"MP405 VALJEVO 1", "NEPOZNAT"}
        });
        IdeaRodaPriceFormatMappingClient client =
                new IdeaRodaPriceFormatMappingClient(
                        null,
                        null,
                        "https://data.gov.rs/sr/datasets/test/",
                        java.time.Duration.ofSeconds(1),
                        1_000_000
                );

        assertThatThrownBy(() -> client.parseWorkbook(workbook))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nepoznat IDEA/Roda cenovnik");
    }

    private byte[] workbook(String[][] rows) throws Exception {
        try (
                XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()
        ) {
            var sheet = workbook.createSheet("Objekti");
            for (int rowIndex = 0; rowIndex < rows.length; rowIndex++) {
                var row = sheet.createRow(rowIndex);
                for (int column = 0;
                     column < rows[rowIndex].length;
                     column++) {
                    row.createCell(column).setCellValue(
                            rows[rowIndex][column]
                    );
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
