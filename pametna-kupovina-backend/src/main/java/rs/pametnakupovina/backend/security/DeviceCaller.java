package rs.pametnakupovina.backend.security;

import jakarta.servlet.ServletRequest;

/**
 * Who is asking, once the request has proven it: the phone, and the account
 * it belongs to. Controllers take it from the security context instead of
 * reading a header themselves, so no endpoint can forget to check.
 *
 * @param accountId       the account whose things this phone may touch
 * @param deviceId        the phone, as listed under the account's devices
 * @param clientTokenHash the phone's old random number, hashed; still the key
 *                        that catalogue feedback is filed under
 */
public record DeviceCaller(long accountId, long deviceId, String clientTokenHash) {

    static final String ATTRIBUTE = DeviceCaller.class.getName();

    /** The phone the security filter proved for this request, or null. */
    public static DeviceCaller of(ServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof DeviceCaller caller ? caller : null;
    }

    public void attachTo(ServletRequest request) {
        request.setAttribute(ATTRIBUTE, this);
    }
}
