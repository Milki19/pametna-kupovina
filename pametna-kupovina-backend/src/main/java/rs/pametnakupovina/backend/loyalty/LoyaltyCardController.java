package rs.pametnakupovina.backend.loyalty;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.security.DeviceCaller;

import java.util.List;

@RestController
@RequestMapping("/api/v1/loyalty-cards")
public class LoyaltyCardController {

    private final LoyaltyCardService cardService;

    public LoyaltyCardController(LoyaltyCardService cardService) {
        this.cardService = cardService;
    }

    @GetMapping
    public List<LoyaltyCard> cards(
            DeviceCaller caller
    ) {
        return cardService.cards(caller.accountId());
    }

    @PostMapping
    public LoyaltyCard add(
            DeviceCaller caller,
            @RequestBody AddLoyaltyCardRequest request
    ) {
        return cardService.add(
                caller.accountId(),
                request.name(),
                request.cardNumber(),
                request.barcodeFormat()
        );
    }

    @DeleteMapping("/{cardId}")
    public void remove(
            DeviceCaller caller,
            @PathVariable("cardId") long cardId
    ) {
        cardService.remove(caller.accountId(), cardId);
    }

    public record AddLoyaltyCardRequest(
            String name,
            String cardNumber,
            String barcodeFormat
    ) {
    }
}
