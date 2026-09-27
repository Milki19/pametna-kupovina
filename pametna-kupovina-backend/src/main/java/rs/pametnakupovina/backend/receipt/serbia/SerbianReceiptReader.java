package rs.pametnakupovina.backend.receipt.serbia;

import org.springframework.stereotype.Component;
import rs.pametnakupovina.backend.receipt.ReceiptReader;

import java.util.Optional;

/**
 * Serbia's receipts: the QR code is a link to the tax office's SUF that
 * carries the receipt's numbers itself, and the same link returns the
 * printed receipt, from which the lines are read.
 */
@Component
public class SerbianReceiptReader implements ReceiptReader {

    private final FiscalVerificationUrlReader verificationUrlReader;
    private final FiscalReceiptClient receiptClient;
    private final FiscalReceiptJournalParser journalParser;

    public SerbianReceiptReader(
            FiscalVerificationUrlReader verificationUrlReader,
            FiscalReceiptClient receiptClient,
            FiscalReceiptJournalParser journalParser
    ) {
        this.verificationUrlReader = verificationUrlReader;
        this.receiptClient = receiptClient;
        this.journalParser = journalParser;
    }

    @Override
    public String marketCode() {
        return "RS";
    }

    @Override
    public ScannedReceipt read(String scannedCode) {
        String verificationUrl = FiscalVerificationUrlReader.canonical(scannedCode);

        return new ScannedReceipt(
                verificationUrl,
                verificationUrlReader.read(verificationUrl)
        );
    }

    @Override
    public Optional<ReceiptLines> lines(String verificationUrl) {
        return receiptClient.fetch(verificationUrl).map(fetched -> {
            var parsed = journalParser.parse(fetched.journal());

            // Pola računa u QR kodu uopšte ne nosi prodavnicu; Poreska uprava
            // je uvek zna, pa se ime dopunjuje čim stigne.
            return new ReceiptLines(
                    fetched.shopName() != null
                            ? fetched.shopName()
                            : parsed.shopName(),
                    fetched.taxIdentificationNumber() != null
                            ? fetched.taxIdentificationNumber()
                            : parsed.taxIdentificationNumber(),
                    parsed.items()
            );
        });
    }
}
