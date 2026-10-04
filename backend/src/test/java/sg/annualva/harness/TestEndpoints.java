package sg.annualva.harness;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.web.ProblemResponses;

/**
 * Test-only endpoints under /api/test/** that let Seam 1 observe the cross-cutting behaviour
 * (error shape, validation, CSRF) without depending on any domain module.
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

        @GetMapping("/api/test/boom")
        public Map<String, String> boom() {
            throw new IllegalStateException("secret internal detail that must never leak");
        }

        @PostMapping("/api/test/echo")
        public Map<String, String> echo(@Valid @RequestBody Echo echo) {
            return Map.of("text", echo.text());
        }
    }
}
