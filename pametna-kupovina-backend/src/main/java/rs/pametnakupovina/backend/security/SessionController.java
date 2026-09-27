package rs.pametnakupovina.backend.security;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

    private final DeviceSessionService sessionService;

    public SessionController(DeviceSessionService sessionService) {
        this.sessionService = sessionService;
    }

    /** Nov telefon, ili stari koji svoj broj menja za sesiju. */
    @PostMapping
    public DeviceSessionService.SessionGrant open(@RequestBody OpenSessionRequest request) {
        return sessionService.open(
                request == null ? null : request.deviceToken(),
                request == null ? null : request.deviceName()
        );
    }

    @PostMapping("/refresh")
    public DeviceSessionService.SessionGrant refresh(@RequestBody RefreshSessionRequest request) {
        return sessionService.refresh(request == null ? null : request.refreshToken());
    }

    @DeleteMapping("/current")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void signOut(DeviceCaller caller) {
        sessionService.signOut(caller);
    }

    public record OpenSessionRequest(String deviceToken, String deviceName) {
    }

    public record RefreshSessionRequest(String refreshToken) {
    }
}
