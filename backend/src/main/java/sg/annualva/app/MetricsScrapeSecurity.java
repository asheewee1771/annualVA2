package sg.annualva.app;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import sg.annualva.web.ProblemResponses;

/**
 * The metrics endpoint's own security chain (IM8 lm-16, ADR 0060). {@code /actuator/prometheus} is for the monitoring
 * system's scraper, which cannot sign in through MCC SSO, so it has a chain of its own, ahead of the application's: no
 * session, no cookie and no CSRF token (the endpoint only answers a GET), and one credential, the bearer token of
 * {@link MonitoringProperties}. A request without it, or with another, is a 401 audited like any other refused request. The token is compared as two SHA-256 digests in constant time, so
 * neither its length nor a matching prefix shows in the time taken. Nothing else under {@code /actuator} is exposed or
 * reachable without a session ({@code management.endpoints.web.exposure.include}).
 */
@Configuration(proxyBeanMethods = false)
class MetricsScrapeSecurity {

    static final String PATH = "/actuator/prometheus";
    private static final String BEARER = "Bearer ";

    @Bean
    @Order(1)
    SecurityFilterChain metricsScrapeChain(HttpSecurity http, ProblemResponses problems, MonitoringProperties properties)
            throws Exception {
        AuthenticationEntryPoint unauthenticated = problems.unauthenticatedEntryPoint();
        http
                .securityMatcher(PATH)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                // A GET with a bearer token in a header and no cookie: there is nothing for a forged request to ride on.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, failure) -> {
                    response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
                    unauthenticated.commence(request, response, failure);
                }))
                .headers(headers -> headers
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true)
                                .requestMatcher(AnyRequestMatcher.INSTANCE))
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER)))
                .addFilterBefore(new BearerTokenFilter(properties.scrapeToken()), AnonymousAuthenticationFilter.class);
        return http.build();
    }

    /** Authenticates a request that carries the configured token; every other request is left anonymous. */
    static final class BearerTokenFilter extends OncePerRequestFilter {

        private final byte[] expected;

        BearerTokenFilter(String token) {
            this.expected = sha256(token);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith(BEARER)
                    && MessageDigest.isEqual(expected, sha256(header.substring(BEARER.length())))) {
                SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                        "metrics-scraper", null, List.of(new SimpleGrantedAuthority("ROLE_METRICS_SCRAPER"))));
            }
            chain.doFilter(request, response);
        }

        private static byte[] sha256(String text) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
            }
        }
    }
}
