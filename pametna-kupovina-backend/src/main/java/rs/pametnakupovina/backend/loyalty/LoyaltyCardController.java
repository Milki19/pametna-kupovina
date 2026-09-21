package rs.pametnakupovina.backend.loyalty;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/loyalty-cards")
public class LoyaltyCardController {

    private static final String CLIENT_TOKEN_HEADER = "X-Client-Token";

    private final LoyaltyCardService cardService;

    public LoyaltyCardController(LoyaltyCardService cardService) {
        this.cardService = cardService;
    }

    @GetMapping
    public List<LoyaltyCard> cards(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken
    ) {
        return cardService.cards(clientToken);
    }

    @PostMapping
    public LoyaltyCard add(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken,
            @RequestBody AddLoyaltyCardRequest request
    ) {
        return cardService.add(
                clientToken,
                request.name(),
                request.cardNumber(),
                request.barcodeFormat()
        );
    }

    @DeleteMapping("/{cardId}")
    public void remove(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken,
            @PathVariable("cardId") long cardId
    ) {
        cardService.remove(clientToken, cardId);
    }

    public record AddLoyaltyCardRequest(
            String name,
            String cardNumber,
            String barcodeFormat
    ) {
    }
}
