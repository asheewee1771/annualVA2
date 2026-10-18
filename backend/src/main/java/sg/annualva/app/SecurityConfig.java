package sg.annualva.app;

import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpMethod;
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
import sg.annualva.access.SignInSecurity;
import sg.annualva.web.ProblemResponses;

/**
 * Deny by default (IM8 ac-1): only the endpoints listed in {@link #PUBLIC_PATHS} are reachable
 * without a session; everything else needs an authenticated session and, at method level, a
 * {@code @PreAuthorize} rule (enforced by the architecture tests). Sign-in, sign-out and the
 * session limits are wired by the access module ({@link SignInSecurity}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
class SecurityConfig {

    /**
     * Anonymous endpoints. Keep this list short and mirrored in the architecture tests.
     * {@code /oauth2/**} and {@code /login/**} are the OIDC round trip (handled by Spring Security's
     * filters); anything else under them, such as {@code POST /login}, reaches no handler and is 404.
     */
    static final String[] PUBLIC_PATHS = {
        "/actuator/health", "/actuator/info", "/api/session", "/api/csrf", "/.well-known/jwks.json",
        "/oauth2/**", "/login/**",
        "/", "/index.html", "/favicon.ico", "/assets/**"
    };

    /** as-3/as-9: minimally permissive content security policy. */
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; "
            + "connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ProblemResponses problems, SignInSecurity signIn)
            throws Exception {
        var csrfTokenRequestHandler = new CsrfTokenRequestAttributeHandler();
        csrfTokenRequestHandler.setCsrfRequestAttributeName(null);
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // The Register is the VA POCs' alone. Also a method rule on each endpoint (ac-1); this one
                        // refuses a stranger before a multipart body is read, so nobody else's file is ever spooled.
                        .requestMatchers("/api/system-register/**").hasRole("VA_POC")
                        // Only an SMO/Tech Lead uploads a Scan Report file (also a method rule); a file of up to 50 MB is
                        // not spooled for anyone else.
                        .requestMatchers(HttpMethod.POST, "/api/submissions/*/files", "/api/submissions/*/files/*/replace")
                        .hasRole("SMO")
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
                        .addHeaderWriter(new StaticHeadersWriter("X-Permitted-Cross-Domain-Policies", "none")));
        signIn.apply(http, problems.unauthenticatedEntryPoint(), problems.tooManyRequestsHandler());
        return http.build();
    }
}
