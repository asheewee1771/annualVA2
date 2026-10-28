package sg.annualva.adapters.oidc;

import org.springframework.context.annotation.Profile;
import com.nimbusds.jose.jwk.ECKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.converter.ClaimTypeConverter;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The MCC SSO login client: registration {@code mcc-sso-login} with explicit endpoints, scope
 * {@code openid afcas}, PKCE S256 plus {@code kc_idp_hint} on every authorization request, and
 * {@code private_key_jwt} (ES512) at the token endpoint. The access module wires these beans into
 * the security filter chain by their Spring types.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!dev-local")
class MccClientConfig {

    static final String REGISTRATION_ID = "mcc-sso-login";
    static final String IDP_HINT_PARAMETER = "kc_idp_hint";

    @Bean
    ECKey mccSigningKey(MccSsoProperties properties) {
        return SigningKeys.load(properties.signingKey());
    }

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(MccSsoProperties properties) {
        ClientRegistration login = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(properties.loginClientId())
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "afcas")
                .issuerUri(properties.issuer())
                .authorizationUri(properties.authorizationUri())
                .tokenUri(properties.tokenUri())
                .jwkSetUri(properties.jwkSetUri())
                .userInfoUri(properties.userInfoUri())
                .userNameAttributeName("sub")
                .clientName("MCC SSO")
                .build();
        return new InMemoryClientRegistrationRepository(login);
    }

    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(ClientRegistrationRepository registrations,
            MccSsoProperties properties) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                DefaultOAuth2AuthorizationRequestResolver.DEFAULT_AUTHORIZATION_REQUEST_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(
                        parameters -> parameters.put(IDP_HINT_PARAMETER, properties.idpHint()))));
        return resolver;
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokenResponseClient(ECKey signingKey,
            MccHttp http) {
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(http.tokenRestClient());
        client.addParametersConverter(new NimbusJwtClientAuthenticationParametersConverter<>(registration -> signingKey));
        return client;
    }

    /** The standard OIDC id token checks, fetching the JWKS through the trusted HTTP client. */
    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(MccHttp http) {
        return registration -> {
            var decoder = NimbusJwtDecoder.withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                    .restOperations(http.jwkSetRestOperations())
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(), new OidcIdTokenValidator(registration)));
            decoder.setClaimSetConverter(new ClaimTypeConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverters()));
            return decoder;
        };
    }
}
