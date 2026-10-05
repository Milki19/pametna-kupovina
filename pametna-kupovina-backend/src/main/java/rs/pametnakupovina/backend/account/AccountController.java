package rs.pametnakupovina.backend.account;

import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import rs.pametnakupovina.backend.security.DeviceCaller;
import rs.pametnakupovina.backend.security.DeviceSessionService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountSignInService signInService;
    private final DeviceSessionService sessionService;
    private final String googleClientId;

    public AccountController(
            AccountSignInService signInService,
            DeviceSessionService sessionService,
            @Value("${account.google.client-id:}") String googleClientId
    ) {
        this.signInService = signInService;
        this.sessionService = sessionService;
        this.googleClientId = googleClientId == null ? "" : googleClientId.strip();
    }

    @GetMapping("/me")
    public AccountSignInService.AccountState me(DeviceCaller caller) {
        return signInService.state(caller);
    }

    /**
     * The web app asks Google for a token itself, so it needs the client ID
     * the server accepts tokens for. Not a secret; empty while not set up.
     */
    @GetMapping("/sign-in/google")
    public GoogleSignInSettings googleSignInSettings() {
        return new GoogleSignInSettings(googleClientId);
    }

    @PostMapping("/sign-in/google")
    public AccountSignInService.AccountState signInWithGoogle(
            DeviceCaller caller,
            @RequestBody GoogleSignInRequest request
    ) {
        return signInService.signInWithGoogle(caller, request.idToken());
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(DeviceCaller caller) {
        signInService.delete(caller);
    }

    @PostMapping("/invite")
    public AccountSignInService.Invite invite(DeviceCaller caller) {
        return signInService.invite(caller);
    }

    @PostMapping("/join")
    public AccountSignInService.AccountState join(
            DeviceCaller caller,
            @RequestBody JoinRequest request
    ) {
        return signInService.join(caller, request.code());
    }

    /** Telefoni na nalogu, da se izgubljen ili tuđ može ukloniti. */
    @GetMapping("/devices")
    public List<DeviceSessionService.DeviceSummary> devices(DeviceCaller caller) {
        return sessionService.devices(caller);
    }

    @DeleteMapping("/devices/{deviceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeDevice(
            DeviceCaller caller,
            @PathVariable("deviceId") long deviceId
    ) {
        sessionService.removeDevice(caller, deviceId);
    }

    public record GoogleSignInRequest(@NotBlank String idToken) {
    }

    public record JoinRequest(@NotBlank String code) {
    }

    public record GoogleSignInSettings(String clientId) {
    }
}
