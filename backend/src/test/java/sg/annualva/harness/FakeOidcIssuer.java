package sg.annualva.harness;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.jspecify.annotations.Nullable;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * The identity-provider fake: an embedded OIDC issuer with authorize, token, JWKS and userinfo
 * endpoints that mints real signed id tokens for whatever {@link TestIdentity} the test chose. It
 * runs outside the Spring context on its own port so the client registration can point at it
 * before the context starts, and it verifies what the application sends: PKCE, and a
 * {@code private_key_jwt} client assertion signed with the key the application publishes at its
 * JWKS endpoint.
 */
public final class FakeOidcIssuer {

    public static final String CLIENT_ID = "app-appfw";
    public static final String SIGNING_KID = "ci-ec521";

    /** What the fake saw at the token endpoint. */
    public record TokenRequest(
            String grantType,
            boolean pkceValid,
            @Nullable String clientAssertionType,
            boolean clientAssertionValid,
            @Nullable String clientAssertionAlgorithm,
            @Nullable String clientAssertionKid) {}

    private record Pending(TestIdentity identity, Map<String, String> authorizationRequest) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static @Nullable FakeOidcIssuer instance;

    private final HttpServer server;
    private final RSAKey issuerKey;
    private final ECKey clientKey;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private volatile @Nullable TestIdentity nextIdentity;
    private volatile @Nullable String clientJwksUrl;
    private volatile @Nullable TokenRequest lastTokenRequest;
    private volatile Map<String, String> lastAuthorizationRequest = Map.of();
    private volatile Duration tokenStall = Duration.ZERO;
    private volatile Duration jwksStall = Duration.ZERO;

    private FakeOidcIssuer() {
        try {
            issuerKey = new RSAKeyGenerator(2048).keyID("fake-issuer-rsa").keyUse(KeyUse.SIGNATURE).generate();
            clientKey = new ECKeyGenerator(Curve.P_521).keyID(SIGNING_KID).generate();
            server = HttpServer.create(new InetSocketAddress(0), 0);
        } catch (JOSEException | IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/authorize", this::authorize);
        server.createContext("/token", this::token);
        server.createContext("/jwks", exchange -> {
            pause(jwksStall);
            json(exchange, 200, new JWKSet(issuerKey.toPublicJWK()).toJSONObject());
        });
        server.createContext("/userinfo", exchange -> json(exchange, 401, Map.of("error", "not used by the application")));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    /** One issuer for the whole suite (the Spring context, and so its client registration, is cached). */
    public static synchronized FakeOidcIssuer start() {
        if (instance == null) {
            instance = new FakeOidcIssuer();
        }
        return instance;
    }

    public String issuer() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /** Points the application's MCC client registration at this issuer and gives it a fresh signing key. */
    public void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("annualva.mcc.issuer", this::issuer);
        registry.add("annualva.mcc.authorization-uri", () -> issuer() + "/authorize");
        registry.add("annualva.mcc.token-uri", () -> issuer() + "/token");
        registry.add("annualva.mcc.jwk-set-uri", () -> issuer() + "/jwks");
        registry.add("annualva.mcc.user-info-uri", () -> issuer() + "/userinfo");
        registry.add("annualva.mcc.signing-key.kid", () -> SIGNING_KID);
        registry.add("annualva.mcc.signing-key.private", () -> pem("PRIVATE KEY", privateKeyDer()));
        registry.add("annualva.mcc.signing-key.public", () -> pem("PUBLIC KEY", publicKeyDer()));
    }

    /** Where to fetch the application's JWKS from when verifying the client assertion. */
    public void clientJwksUrl(String url) {
        this.clientJwksUrl = url;
    }

    /** Makes the token endpoint answer only after this long (zero for normal behaviour). */
    public void stallTokenEndpoint(Duration stall) {
        this.tokenStall = stall;
    }

    /** Makes the JWKS endpoint answer only after this long (zero for normal behaviour). */
    public void stallJwksEndpoint(Duration stall) {
        this.jwksStall = stall;
    }

    private static void pause(Duration stall) {
        try {
            Thread.sleep(stall);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The identity asserted by the next authorization request. */
    public void nextSignIn(TestIdentity identity) {
        this.nextIdentity = identity;
    }

    public Map<String, String> lastAuthorizationRequest() {
        return lastAuthorizationRequest;
    }

    public TokenRequest lastTokenRequest() {
        TokenRequest request = lastTokenRequest;
        if (request == null) {
            throw new IllegalStateException("the token endpoint has not been called");
        }
        return request;
    }

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> params = decode(exchange.getRequestURI().getRawQuery());
        lastAuthorizationRequest = params;
        TestIdentity identity = nextIdentity;
        if (identity == null) {
            json(exchange, 400, Map.of("error", "no identity was set for this sign-in"));
            return;
        }
        nextIdentity = null;
        String code = UUID.randomUUID().toString();
        codes.put(code, new Pending(identity, params));
        String location = params.get("redirect_uri") + "?code=" + code + "&state=" + encode(params.get("state"));
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        pause(tokenStall);
        Map<String, String> form = decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Pending pending = codes.remove(form.get("code"));
        if (pending == null || !"authorization_code".equals(form.get("grant_type"))) {
            lastTokenRequest = new TokenRequest(form.get("grant_type"), false, null, false, null, null);
            json(exchange, 400, Map.of("error", "invalid_grant"));
            return;
        }
        boolean pkceValid = s256(form.get("code_verifier")).equals(pending.authorizationRequest().get("code_challenge"))
                && form.get("redirect_uri").equals(pending.authorizationRequest().get("redirect_uri"));
        String assertionType = form.get("client_assertion_type");
        Assertion assertion = verifyClientAssertion(form.get("client_assertion"));
        lastTokenRequest = new TokenRequest("authorization_code", pkceValid, assertionType, assertion.valid(),
                assertion.algorithm(), assertion.kid());
        if (!pkceValid || !assertion.valid()
                || !"urn:ietf:params:oauth:client-assertion-type:jwt-bearer".equals(assertionType)) {
            json(exchange, 401, Map.of("error", "invalid_client"));
            return;
        }
        var response = new LinkedHashMap<String, Object>();
        response.put("access_token", UUID.randomUUID().toString());
        response.put("token_type", "Bearer");
        response.put("expires_in", 300);
        response.put("scope", "openid afcas");
        response.put("id_token", idToken(pending));
        json(exchange, 200, response);
    }

    private record Assertion(boolean valid, @Nullable String algorithm, @Nullable String kid) {}

    private Assertion verifyClientAssertion(@Nullable String assertion) {
        String jwksUrl = clientJwksUrl;
        if (assertion == null || jwksUrl == null) {
            return new Assertion(false, null, null);
        }
        try {
            SignedJWT jwt = SignedJWT.parse(assertion);
            String kid = jwt.getHeader().getKeyID();
            String algorithm = jwt.getHeader().getAlgorithm().getName();
            HttpResponse<String> jwks = http.send(HttpRequest.newBuilder(URI.create(jwksUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            var key = JWKSet.parse(jwks.body()).getKeyByKeyId(kid);
            boolean signatureValid = key instanceof ECKey ecKey && jwt.verify(new ECDSAVerifier(ecKey));
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            boolean claimsValid = CLIENT_ID.equals(claims.getIssuer()) && CLIENT_ID.equals(claims.getSubject())
                    && claims.getAudience().contains(issuer() + "/token")
                    && claims.getExpirationTime() != null && claims.getExpirationTime().after(new Date());
            return new Assertion(signatureValid && claimsValid, algorithm, kid);
        } catch (ParseException | JOSEException e) {
            return new Assertion(false, null, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String idToken(Pending pending) {
        TestIdentity identity = pending.identity();
        Instant now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject("kc-" + (identity.ssoId() != null ? identity.ssoId() : UUID.randomUUID()))
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", identity.tamperedNonce() ? "tampered" : pending.authorizationRequest().get("nonce"))
                .claim("name", identity.name());
        if (identity.ssoId() != null) {
            if (identity.nestedAfcasClaim()) {
                claims.claim("afcas", Map.of("uuid", identity.ssoId()));
            } else {
                claims.claim("afcas.uuid", identity.ssoId());
            }
        }
        if (identity.email() != null) {
            claims.claim("email", identity.email());
        }
        if (identity.emailVerified() != null) {
            claims.claim("email_verified", identity.emailVerified());
        }
        if (identity.authTime() != null) {
            claims.claim("auth_time", identity.authTime().getEpochSecond());
        }
        if (identity.amr() != null) {
            claims.claim("amr", identity.amr());
        }
        if (identity.acr() != null) {
            claims.claim("acr", identity.acr());
        }
        identity.extraClaims().forEach(claims::claim);
        try {
            var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(issuerKey.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(issuerKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void json(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    static Map<String, String> decode(@Nullable String query) {
        var result = new LinkedHashMap<String, String>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(name, value);
        }
        return result;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String s256(@Nullable String verifier) {
        if (verifier == null) {
            return "";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] privateKeyDer() {
        try {
            return clientKey.toECPrivateKey().getEncoded();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] publicKeyDer() {
        try {
            return clientKey.toECPublicKey().getEncoded();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Base64 of a PEM block, the shape application-dev-mcc.yml carries (CRLF line ends like the generator script). */
    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der);
        String text = "-----BEGIN " + label + "-----\r\n" + body + "\r\n-----END " + label + "-----\r\n";
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.US_ASCII));
    }
}
