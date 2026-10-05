package sg.annualva.adapters.oidc;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.Name;
import org.springframework.validation.annotation.Validated;

/**
 * MCC SSO settings (prefix {@code annualva.mcc}). Endpoints are explicit: discovery through an
 * {@code issuer-uri} would bypass the SSL bundle that trusts the local Keycloak certificate.
 *
 * @param issuer the {@code iss} value every id token must carry
 * @param sslBundle name of the Spring SSL bundle trusting the provider's certificate; null for the system trust
 * @param connectTimeout how long to wait to reach the provider (token endpoint, JWKS)
 * @param readTimeout how long to wait for the provider's answer; a stalled provider must not hold a request thread
 * @param loginClientId the browser-login client (MTM onboarding: {@code app-appfw})
 * @param idpHint Keycloak's {@code kc_idp_hint}, selecting the staff identity provider
 * @param signingKey the EC P-521 key pair the application signs client assertions with
 */
@ConfigurationProperties("annualva.mcc")
@Validated
public record MccSsoProperties(
        @NotBlank String issuer,
        @NotBlank String authorizationUri,
        @NotBlank String tokenUri,
        @NotBlank String jwkSetUri,
        @NotBlank String userInfoUri,
        @Nullable String sslBundle,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @NotBlank String loginClientId,
        @NotBlank String idpHint,
        @NotNull @Valid SigningKey signingKey) {

    /**
     * @param kid the key id published in the JWKS and sent in every client assertion header
     * @param privatePem base64 of the PKCS#8 PEM block (never a literal in a deployed profile)
     * @param publicPem base64 of the SPKI PEM block
     */
    public record SigningKey(
            @NotBlank String kid,
            @Name("private") @NotBlank String privatePem,
            @Name("public") @NotBlank String publicPem) {}
}
