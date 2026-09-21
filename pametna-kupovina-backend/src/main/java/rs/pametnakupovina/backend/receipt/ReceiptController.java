package rs.pametnakupovina.backend.receipt;

import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/receipts")
public class ReceiptController {

    private static final String CLIENT_TOKEN_HEADER = "X-Client-Token";

    private final ReceiptService receiptService;

    public ReceiptController(ReceiptService receiptService) {
        this.receiptService = receiptService;
    }

    @PostMapping
    public Receipt scan(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken,
            @RequestBody ScanReceiptRequest request
    ) {
        return receiptService.scan(clientToken, request.verificationUrl());
    }

    @GetMapping
    public List<Receipt> history(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken,
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        return receiptService.history(clientToken, limit);
    }

    @GetMapping("/spending")
    public ReceiptService.Spending spending(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken
    ) {
        return receiptService.spending(clientToken);
    }

    @GetMapping("/habits")
    public List<ReceiptRepository.Habit> habits(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken,
            @RequestParam(name = "limit", defaultValue = "20") int limit
    ) {
        return receiptService.habits(clientToken, limit);
    }

    @GetMapping("/{receiptId}")
    public Receipt one(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken,
            @PathVariable("receiptId") long receiptId
    ) {
        return receiptService.one(clientToken, receiptId);
    }

    public record ScanReceiptRequest(@NotBlank String verificationUrl) {
    }
}
