package sg.annualva.app;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.header.writers.CrossOriginOpenerPolicyHeaderWriter.CrossOriginOpenerPolicy;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;
import org.springframework.security.web.header.writers.StaticHeadersWriter;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import sg.annualva.web.ProblemResponses;

/**
 * Deny by default (IM8 ac-1): only the endpoints listed in {@link #PUBLIC_PATHS} are reachable
 * without a session; everything else needs an authenticated session and, at method level, a
 * {@code @PreAuthorize} rule (enforced by the architecture tests). Sign-in itself is wired by the
 * access module.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    /** Anonymous endpoints. Keep this list short and mirrored in the architecture tests. */
    static final String[] PUBLIC_PATHS = {
        "/actuator/health", "/actuator/info", "/api/session", "/api/csrf",
        "/", "/index.html", "/favicon.ico", "/assets/**"
    };

    /** as-3/as-9: minimally permissive content security policy. */
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; "
            + "connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemResponses problems) throws Exception {
        var csrfTokenRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfTokenRequestHandler.setCsrfRequestAttributeName(null);
        return http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                // Synchronizer token pattern: the SPA bootstraps the token from GET /api/csrf and sends it
                // back in the X-CSRF-TOKEN header. Cookie/double-submit is prohibited by the org standard.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(csrfTokenRequestHandler))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problems.unauthenticatedEntryPoint())
                        .accessDeniedHandler(problems.accessDeniedHandler()))
                .requestCache(cache -> cache.disable())
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY))
                        // as-10: one year, on every response (not only requests the container saw as secure)
                        .httpStrictTransportSecurity(hsts -> hsts
                                .maxAgeInSeconds(31536000)
                                .includeSubDomains(true)
                                .requestMatcher(AnyRequestMatcher.INSTANCE))
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                        .crossOriginOpenerPolicy(coop -> coop.policy(CrossOriginOpenerPolicy.SAME_ORIGIN))
                        .addHeaderWriter(new StaticHeadersWriter("Permissions-Policy",
                                "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                        .addHeaderWriter(new StaticHeadersWriter("X-Permitted-Cross-Domain-Policies", "none")))
                .build();
    }
}
