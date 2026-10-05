package sg.annualva.adapters.oidc;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.KeyUse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Loads the configured EC P-521 key pair (base64 PEM, as the generator script prints it) into a Nimbus JWK. */
final class SigningKeys {

    private SigningKeys() {}

    static ECKey load(MccSsoProperties.SigningKey key) {
        try {
            KeyFactory ec = KeyFactory.getInstance("EC");
            var privateKey = (ECPrivateKey) ec.generatePrivate(new PKCS8EncodedKeySpec(der(key.privatePem())));
            var publicKey = (ECPublicKey) ec.generatePublic(new X509EncodedKeySpec(der(key.publicPem())));
            Curve curve = Curve.forECParameterSpec(publicKey.getParams());
            if (!Curve.P_521.equals(curve)) {
                throw new IllegalStateException("The MCC signing key must be on curve P-521 (ES512)");
            }
            return new ECKey.Builder(curve, publicKey)
                    .privateKey(privateKey)
                    .keyID(key.kid())
                    .algorithm(JWSAlgorithm.ES512)
                    .keyUse(KeyUse.SIGNATURE)
                    .build();
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            // No key material in the message: the cause is enough for the operator.
            throw new IllegalStateException("The MCC signing key is not a valid EC key pair", e);
        }
    }

    /** Base64 of a PEM block to DER: unwrap, drop the armour lines and line breaks, decode. */
    private static byte[] der(String base64Pem) {
        String pem = new String(Base64.getDecoder().decode(base64Pem.strip()), StandardCharsets.US_ASCII);
        StringBuilder body = new StringBuilder();
        for (String line : pem.split("\\R")) {
            if (!line.startsWith("-----")) {
                body.append(line.strip());
            }
        }
        return Base64.getDecoder().decode(body.toString());
    }
}
