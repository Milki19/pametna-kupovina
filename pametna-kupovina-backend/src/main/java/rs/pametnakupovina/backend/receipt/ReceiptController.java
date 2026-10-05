package rs.pametnakupovina.backend.receipt;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;
    private final ReceiptFileCodes fileCodes;

    public ReceiptController(ReceiptService receiptService, ReceiptFileCodes fileCodes) {
        this.receiptService = receiptService;
        this.fileCodes = fileCodes;
    }

    @PostMapping
    public Receipt scan(
            DeviceCaller caller,
            @RequestBody ScanReceiptRequest request
    ) {
        return receiptService.scan(caller.accountId(), request.verificationUrl());
    }

    /**
     * Digitalni račun iz aplikacije trgovine: screenshot ili PDF. Kod se
     * čita pre nego što se otvori transakcija, jer čitanje slike traje.
     */
    @PostMapping(path = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Receipt scanFile(
            DeviceCaller caller,
            @RequestParam("file") MultipartFile file
    ) throws IOException {
        return receiptService.scanCodes(caller.accountId(), fileCodes.read(file.getBytes()));
    }

    @GetMapping
    public List<Receipt> history(
            DeviceCaller caller,
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return receiptService.history(caller.accountId(), limit);
    }

    @GetMapping("/spending")
    public ReceiptService.Spending spending(
            DeviceCaller caller,
            @RequestParam(name = "month", required = false) java.time.LocalDate month
    ) {
        return receiptService.spending(caller.accountId(), month);
    }

    @GetMapping("/habits")
    public List<ReceiptRepository.Habit> habits(
            DeviceCaller caller,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        return receiptService.habits(caller.accountId(), limit);
    }

    @GetMapping("/{receiptId}")
    public Receipt one(
            DeviceCaller caller,
            @PathVariable("receiptId") long receiptId
    ) {
        return receiptService.one(caller.accountId(), receiptId);
    }

    public record ScanReceiptRequest(@NotBlank String verificationUrl) {
    }
}
