package sg.annualva.adapters.oidc;

import org.springframework.context.annotation.Profile;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.function.SingletonSupplier;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.RestTemplate;

/**
 * The outbound HTTP to MCC SSO (token endpoint, JWKS), trusting the configured SSL bundle when
 * there is one. The bundle is opened on first use so the application still starts when the
 * local mock stack's certificate file is absent (dp-3: trust is never turned off, only deferred).
 */
@Component
@Profile("!dev-local")
class MccHttp {

    private final ClientHttpRequestFactory requestFactory;

    MccHttp(MccSsoProperties properties, ObjectProvider<SslBundles> sslBundles) {
        String bundle = properties.sslBundle();
        this.requestFactory = bundle == null
                ? withTimeouts(HttpClient.newBuilder(), properties)
                : new TrustedRequestFactory(() -> withTimeouts(HttpClient.newBuilder()
                        .sslContext(sslBundles.getObject().getBundle(bundle).createSslContext()), properties));
    }

    /** A stalled provider ends the call with an error (a provider-error refusal) instead of holding a thread. */
    private static JdkClientHttpRequestFactory withTimeouts(HttpClient.Builder client, MccSsoProperties properties) {
        var factory = new JdkClientHttpRequestFactory(client.connectTimeout(properties.connectTimeout()).build());
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    /** For the token endpoint: the converters Spring Security's token response client expects. */
    RestClient tokenRestClient() {
        return RestClient.builder()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    /** For fetching the provider's JWKS when validating id tokens. */
    RestOperations jwkSetRestOperations() {
        return new RestTemplate(requestFactory);
    }

    /** Builds the TLS-configured delegate on the first request, not at startup. */
    private static final class TrustedRequestFactory implements ClientHttpRequestFactory {

        private final Supplier<ClientHttpRequestFactory> delegate;

        private TrustedRequestFactory(Supplier<ClientHttpRequestFactory> delegate) {
            this.delegate = SingletonSupplier.of(delegate);
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            return delegate.get().createRequest(uri, httpMethod);
        }
    }
}
