package rs.pametnakupovina.backend.receipt;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.GenericMultipleBarcodeReader;
import com.google.zxing.qrcode.QRCodeReader;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Digitalni račun stiže kao slika ekrana ili PDF iz aplikacije trgovine, a
 * na njemu je isti QR kod kao na papiru. Ovde se iz takvog fajla izvuku svi
 * QR kodovi; šta je od njih račun, odlučuje čitač tržišta. Fajl se čita samo
 * u memoriji i nigde se ne čuva.
 */
@Component
public class ReceiptFileCodes {

    /** Veća slika od ovoga nije screenshot nego pokušaj da se zauzme server. */
    private static final long MOST_PIXELS = 25_000_000L;
    /** Uvećava se samo screenshot telefona, ne fotografija od 12 MP. */
    private static final long MOST_PIXELS_TO_ENLARGE = 6_000_000L;
    private static final int MOST_PDF_PAGES = 3;
    private static final float PDF_DPI = 200f;

    private static final Map<DecodeHintType, Object> HINTS = Map.of(
            DecodeHintType.TRY_HARDER, Boolean.TRUE,
            DecodeHintType.CHARACTER_SET, "UTF-8"
    );

    /** @return tekst svakog QR koda u fajlu, bez ponavljanja, redom kako su nađeni */
    public List<String> read(byte[] file) {
        List<BufferedImage> images = looksLikePdf(file) ? pdfPages(file) : List.of(image(file));
        Set<String> codes = new LinkedHashSet<>();

        for (BufferedImage image : images) {
            codes.addAll(qrCodes(image));
        }
        return new ArrayList<>(codes);
    }

    private static boolean looksLikePdf(byte[] file) {
        return file.length > 4 && file[0] == '%' && file[1] == 'P' && file[2] == 'D' && file[3] == 'F';
    }

    private static BufferedImage image(byte[] file) {
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(file))) {
            Iterator<ImageReader> readers = input == null ? null : ImageIO.getImageReaders(input);

            if (readers == null || !readers.hasNext()) {
                throw unreadable("Fajl nije slika ni PDF. Pošalji screenshot (PNG ili JPG) ili PDF računa.");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                // Veličina se zna iz zaglavlja, pre nego što se slika raspakuje.
                if ((long) reader.getWidth(0) * reader.getHeight(0) > MOST_PIXELS) {
                    throw unreadable("Slika je prevelika. Pošalji običan screenshot računa.");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException broken) {
            if (broken instanceof ResponseStatusException refused) {
                throw refused;
            }
            throw unreadable("Slika ne može da se pročita. Pošalji screenshot (PNG ili JPG) ili PDF računa.");
        }
    }

    private static List<BufferedImage> pdfPages(byte[] file) {
        try (PDDocument document = Loader.loadPDF(file)) {
            PDFRenderer renderer = new PDFRenderer(document);
            List<BufferedImage> pages = new ArrayList<>();

            for (int page = 0; page < Math.min(document.getNumberOfPages(), MOST_PDF_PAGES); page++) {
                pages.add(renderer.renderImageWithDPI(page, PDF_DPI, ImageType.GRAY));
            }
            return pages;
        } catch (IOException | RuntimeException broken) {
            throw unreadable("PDF ne može da se pročita. Pošalji screenshot računa.");
        }
    }

    /**
     * Prvo kako je, pa uvećano, pa umanjeno, svaki put na dva načina
     * razdvajanja crnog i belog: JPEG sa telefona ume da zamuti ivice gustog
     * koda tačno toliko da ga jedna veličina ne pročita, a druga pročita.
     */
    private static List<String> qrCodes(BufferedImage image) {
        List<Double> scales = (long) image.getWidth() * image.getHeight() <= MOST_PIXELS_TO_ENLARGE
                ? List.of(1.0, 1.5, 0.5)
                : List.of(1.0, 0.5);

        for (double scale : scales) {
            LuminanceSource source = luminance(scale == 1.0 ? image : scaled(image, scale));
            for (BinaryBitmap bitmap : List.of(
                    new BinaryBitmap(new HybridBinarizer(source)),
                    new BinaryBitmap(new GlobalHistogramBinarizer(source)))) {
                List<String> found = decode(bitmap);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return List.of();
    }

    private static List<String> decode(BinaryBitmap bitmap) {
        List<String> found = new ArrayList<>();

        try {
            for (Result result : new GenericMultipleBarcodeReader(new QRCodeReader())
                    .decodeMultiple(bitmap, HINTS)) {
                found.add(result.getText());
            }
        } catch (NotFoundException none) {
            // Nijedan kod ovim načinom; pokušava se sledeći.
        }

        if (found.isEmpty()) {
            try {
                found.add(new QRCodeReader().decode(bitmap, HINTS).getText());
            } catch (ReaderException none) {
                // I dalje ništa.
            }
        }
        return found;
    }

    private static BufferedImage scaled(BufferedImage image, double scale) {
        BufferedImage resized = new BufferedImage(
                Math.max(1, (int) (image.getWidth() * scale)),
                Math.max(1, (int) (image.getHeight() * scale)),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, resized.getWidth(), resized.getHeight());
        g.drawImage(image, 0, 0, resized.getWidth(), resized.getHeight(), null);
        g.dispose();
        return resized;
    }

    private static ResponseStatusException unreadable(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    /**
     * Svetlina svake tačke slike, onako kako je ZXing traži. Y ravan iz YUV-a
     * je baš to, a taj izvor ume i da iseče deo slike kad traži više kodova.
     */
    private static LuminanceSource luminance(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[] row = new int[width];
        byte[] luminance = new byte[width * height];

        // Red po red, da se cela slika ne drži još jednom kao int[].
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int x = 0; x < width; x++) {
                int pixel = row[x];
                int gray = (((pixel >> 16) & 0xff) * 306 + ((pixel >> 8) & 0xff) * 601 + (pixel & 0xff) * 117) >> 10;
                // Providno je pozadina, a pozadina računa je bela.
                luminance[y * width + x] = (byte) ((pixel >>> 24) < 128 ? 255 : gray);
            }
        }
        return new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false);
    }
}
