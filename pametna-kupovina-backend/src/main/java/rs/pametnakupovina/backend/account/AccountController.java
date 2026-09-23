package rs.pametnakupovina.backend.account;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final String CLIENT_TOKEN_HEADER = "X-Client-Token";

    private final AccountSignInService signInService;

    public AccountController(AccountSignInService signInService) {
        this.signInService = signInService;
    }

    @GetMapping("/me")
    public AccountSignInService.AccountState me(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken
    ) {
        return signInService.state(clientToken);
    }

    @PostMapping("/sign-in/google")
    public AccountSignInService.AccountState signInWithGoogle(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken,
            @RequestBody GoogleSignInRequest request
    ) {
        return signInService.signInWithGoogle(clientToken, request.idToken());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(CLIENT_TOKEN_HEADER) String clientToken) {
        signInService.delete(clientToken);
    }

    @PostMapping("/invite")
    public AccountSignInService.Invite invite(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken
    ) {
        return signInService.invite(clientToken);
    }

    @PostMapping("/join")
    public AccountSignInService.AccountState join(
            @RequestHeader(CLIENT_TOKEN_HEADER) String clientToken,
            @RequestBody JoinRequest request
    ) {
        return signInService.join(clientToken, request.code());
    }

    public record GoogleSignInRequest(@NotBlank String idToken) {
    }

    public record JoinRequest(@NotBlank String code) {
    }
}
