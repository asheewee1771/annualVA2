package sg.annualva.boundary;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The identity-provider port: turns the verified token claims of a sign-in into the identity the
 * access module links to a user. The production adapter knows MCC SSO's claim shapes; the Seam 1
 * harness drives the real adapter through a fake issuer.
 */
public interface IdentityProvider {

    Identity identityFrom(Map<String, Object> claims);

    /**
     * @param ssoId the stable identifier from the identity provider ({@code afcas.uuid}); null when absent
     * @param email the verified email; null when the provider sent none
     * @param emailVerified true only when the provider asserts the email is verified ({@code email_verified})
     * @param displayName the person's name for the header; null when absent
     * @param authTime when the provider authenticated the person; null when absent (sign-in fails closed)
     * @param amr authentication method references, empty when absent
     * @param acr authentication context class reference; null when absent
     */
    record Identity(
            @Nullable String ssoId,
            @Nullable String email,
            boolean emailVerified,
            @Nullable String displayName,
            @Nullable Instant authTime,
            List<String> amr,
            @Nullable String acr) {}
}
