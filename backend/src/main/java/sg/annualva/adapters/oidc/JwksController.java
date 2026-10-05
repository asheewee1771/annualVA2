package sg.annualva.adapters.oidc;

import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the client-assertion signing key so MCC SSO can verify
 * {@code private_key_jwt}. Anonymous by design (listed in SecurityConfig.PUBLIC_PATHS); public
 * key material only.
 */
@RestController
public class JwksController {

    private final ECKey signingKey;

    JwksController(ECKey signingKey) {
        this.signingKey = signingKey;
    }

    @GetMapping(value = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("permitAll()")
    public Map<String, Object> jwks() {
        return new JWKSet(signingKey.toPublicJWK()).toJSONObject();
    }
}
