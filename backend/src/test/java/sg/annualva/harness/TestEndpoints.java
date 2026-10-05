package sg.annualva.harness;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.access.AppUserStore;
import sg.annualva.web.ProblemResponses;

/**
 * Test-only endpoints under /api/test/** that let Seam 1 observe the cross-cutting behaviour
 * (error shape, validation, CSRF) and arrange state no public API offers yet (a pre-registered
 * email, as the System Register will create). /api/probe/** sits behind the real security chain.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestEndpoints {

    @Bean
    @Order(0)
    SecurityFilterChain testEndpointsChain(HttpSecurity http, ProblemResponses problems) throws Exception {
        var handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return http
                .securityMatcher("/api/test/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(handler))
                .exceptionHandling(ex -> ex.accessDeniedHandler(problems.accessDeniedHandler()))
                .build();
    }

    /** Picked up by component scanning (it lives under sg.annualva on the test classpath). */
    @RestController
    public static class TestController {

        public record Echo(@NotBlank String text) {}

        public record PreRegister(@NotBlank String email) {}

        private final AppUserStore users;
        private final Clock clock;

        public TestController(AppUserStore users, Clock clock) {
            this.users = users;
            this.clock = clock;
        }

        @GetMapping("/api/test/boom")
        public Map<String, String> boom() {
            throw new IllegalStateException("secret internal detail that must never leak");
        }

        @PostMapping("/api/test/echo")
        public Map<String, String> echo(@Valid @RequestBody Echo echo) {
            return Map.of("text", echo.text());
        }

        /** What the System Register will do when it names a person who has not signed in yet. */
        @PostMapping("/api/test/users")
        public Map<String, String> preRegister(@Valid @RequestBody PreRegister body) {
            return Map.of("uuid", users.preRegister(body.email(), clock.instant()).id().toString());
        }

        /** Behind the production chain: 401 without a session, the principal name with one. */
        @GetMapping("/api/probe/whoami")
        public Map<String, String> whoami(Authentication authentication) {
            return Map.of("name", authentication.getName());
        }
    }
}
