package rs.pametnakupovina.backend.receipt;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.util.List;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping
    public Receipt scan(
            DeviceCaller caller,
            @RequestBody ScanReceiptRequest request
    ) {
        return receiptService.scan(caller.accountId(), request.verificationUrl());
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
