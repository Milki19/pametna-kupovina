package rs.pametnakupovina.backend.receipt;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Digitalni računi (Lidl Plus, web stranice trgovina) imaju isti QR kao
 * papirni, samo na ekranu: kod je dug (~870 znakova), a oko njega je tekst
 * računa. Slike se ovde prave iz koda, da u repozitorijum ne uđe nečiji račun.
 */
class ReceiptFileCodesTest {

    /** Isti oblik kao na računima sa web stranice: adresa sa :443 i dug kod. */
    private static final String RECEIPT = "https://suf.purs.gov.rs:443/v/?vl="
            + "A0xVRURWOExCRHQxT3YxbzA0AQAANAEAAEDr0gEAAAAAAAABg82GSNIAAApNaWxvamtvIDIy"
            + "x".repeat(780);

    private final ReceiptFileCodes codes = new ReceiptFileCodes();

    @Test
    void aScreenshotOfAReceiptGivesItsCode() throws Exception {
        assertThat(codes.read(encode(screenshot(RECEIPT), "png"))).containsExactly(RECEIPT);
        assertThat(codes.read(encode(screenshot(RECEIPT), "jpg"))).containsExactly(RECEIPT);
    }

    @Test
    void aPdfReceiptGivesItsCode() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawImage(LosslessFactory.createFromImage(document, qr(RECEIPT, 400)), 100, 250, 400, 400);
            }
            document.save(out);

            assertThat(codes.read(out.toByteArray())).containsExactly(RECEIPT);
        }
    }

    @Test
    void aPictureWithoutACodeGivesNothing() throws Exception {
        BufferedImage blank = new BufferedImage(600, 900, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = blank.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 900);
        g.dispose();

        assertThat(codes.read(encode(blank, "png"))).isEmpty();
    }

    @Test
    void somethingThatIsNotAPictureIsRefusedWithAReason() {
        assertThatThrownBy(() -> codes.read("ovo nije slika".getBytes()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("nije slika ni PDF");
        assertThatThrownBy(() -> codes.read("%PDF-1.7 pokvaren".getBytes()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("PDF ne može da se pročita");
    }

    /** Kao ekran telefona: bela strana, QR u sredini, tekst iznad. */
    private static BufferedImage screenshot(String code) throws Exception {
        BufferedImage screen = new BufferedImage(933, 2000, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 933, 2000);
        g.setColor(Color.BLACK);
        g.drawString("ПФР број рачуна: LUEDV8LB-Dt1Ov1o0-308", 100, 300);
        g.drawImage(qr(code, 700), 116, 500, null);
        g.dispose();
        return screen;
    }

    private static BufferedImage qr(String code, int size) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}
