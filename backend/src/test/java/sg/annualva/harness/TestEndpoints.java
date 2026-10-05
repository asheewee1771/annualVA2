package sg.annualva.harness;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.access.AccessGuard;
import sg.annualva.access.AppUserStore;
import sg.annualva.access.GrantSource;
import sg.annualva.access.Need;
import sg.annualva.access.Role;
import sg.annualva.access.StepUp;
import sg.annualva.access.RoleGrantStore;
import sg.annualva.web.ProblemResponses;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Test-only endpoints under /api/test/** that let Seam 1 observe the cross-cutting behaviour
 * (error shape, validation, CSRF) and arrange state no public API offers yet (a pre-registered
 * email, a role grant and a System, as the System Register will create). /api/probe/** sits behind
 * the real security chain and stands in for the endpoints later slices add: it calls the same
 * method-level rules and {@link AccessGuard} those endpoints must.
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

        /** @param source CONFIG, REGISTER or GRANT; defaults to REGISTER, what a Register apply will write */
        public record GrantRequest(
                @NotBlank String role,
                @NotBlank String email,
                @Nullable String systemId,
                @Nullable String source,
                @Nullable String expiresAt) {}

        private static final String REVOKE = "UPDATE role_grant SET revoked_at = :now WHERE id = :id";
        private static final String SUSPEND = "UPDATE role_grant SET suspended_at = :now WHERE id = :id";
        private static final String LAPSE = "UPDATE role_grant SET lapsed_at = :now WHERE id = :id";
        private static final String LIST_GRANTS =
                "SELECT id, role, source, system_id, user_id IS NOT NULL AS attached, last_used_at "
                + "FROM role_grant WHERE email = CAST(:email AS citext) ORDER BY granted_at, id";

        /** The Systems that "exist", standing in for the System Register's table. */
        private final Set<UUID> systems = ConcurrentHashMap.newKeySet();

        private final AppUserStore users;
        private final RoleGrantStore grants;
        private final AccessGuard guard;
        private final StepUp stepUp;
        private final JdbcClient jdbc;
        private final Clock clock;

        public TestController(AppUserStore users, RoleGrantStore grants, AccessGuard guard, StepUp stepUp, JdbcClient jdbc,
                Clock clock) {
            this.users = users;
            this.grants = grants;
            this.guard = guard;
            this.stepUp = stepUp;
            this.jdbc = jdbc;
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

        /** What a Register apply will do: a System comes into existence. */
        @PostMapping("/api/test/systems")
        public Map<String, String> createSystem() {
            UUID id = UUID.randomUUID();
            systems.add(id);
            return Map.of("id", id.toString());
        }

        /** What a Register apply, configuration and the later Auditor grant will do: write a role grant. */
        @PostMapping("/api/test/grants")
        public Map<String, String> grant(@Valid @RequestBody GrantRequest body) {
            var grant = grants.grant(
                    Role.valueOf(body.role()),
                    body.email(),
                    body.systemId() == null ? null : UUID.fromString(body.systemId()),
                    body.source() == null ? GrantSource.REGISTER : GrantSource.valueOf(body.source()),
                    null,
                    body.expiresAt() == null ? null : Instant.parse(body.expiresAt()),
                    clock.instant());
            return Map.of("id", grant.id().toString());
        }

        /** The states that later slices own (revoke by Register change, lapse job, role-holder review). */
        @PostMapping("/api/test/grants/{id}/{change}")
        public Map<String, String> change(@PathVariable UUID id, @PathVariable String change) {
            String sql = switch (change) {
                case "revoke" -> REVOKE;
                case "suspend" -> SUSPEND;
                case "lapse" -> LAPSE;
                default -> throw new IllegalArgumentException("unknown change");
            };
            jdbc.sql(sql).param("id", id).param("now", clock.instant().atOffset(java.time.ZoneOffset.UTC)).update();
            return Map.of("change", change);
        }

        /** Observation until a role-holder read API exists: the grants held under one email. */
        @GetMapping("/api/test/grants")
        public List<Map<String, Object>> listGrants(@RequestParam String email) {
            return jdbc.sql(LIST_GRANTS).param("email", email).query((rs, row) -> {
                Map<String, Object> view = new java.util.LinkedHashMap<>();
                view.put("id", rs.getObject("id", UUID.class).toString());
                view.put("role", rs.getString("role"));
                view.put("source", rs.getString("source"));
                view.put("systemId", rs.getObject("system_id", UUID.class) == null ? null
                        : rs.getObject("system_id", UUID.class).toString());
                view.put("attached", rs.getBoolean("attached"));
                var lastUsed = rs.getObject("last_used_at", java.time.OffsetDateTime.class);
                view.put("lastUsedAt", lastUsed == null ? null : lastUsed.toInstant().toString());
                return view;
            }).list();
        }

        /** Behind the production chain: 401 without a session, the principal name with one. */
        @GetMapping("/api/probe/whoami")
        public Map<String, String> whoami(Authentication authentication) {
            return Map.of("name", authentication.getName());
        }

        /** Stands in for a sensitive command (the Register apply, endorsing): it must ask for a fresh sign-in first. */
        @PostMapping("/api/probe/sensitive")
        @PreAuthorize("isAuthenticated()")
        public Map<String, String> sensitive() {
            stepUp.require("probe-sensitive");
            return Map.of("done", "yes");
        }

        @GetMapping("/api/probe/va-poc")
        @PreAuthorize("hasRole('VA_POC')")
        public Map<String, String> vaPocOnly() {
            return Map.of("area", "va-poc");
        }

        @GetMapping("/api/probe/cyber-manager")
        @PreAuthorize("hasRole('CYBER_MANAGER')")
        public Map<String, String> cyberManagerOnly() {
            return Map.of("area", "cyber-manager");
        }

        /** A service path that addresses one System: ownership first, then existence, one answer for both. */
        @GetMapping("/api/probe/systems/{id}/{need}")
        @PreAuthorize("isAuthenticated()")
        public Map<String, String> system(@PathVariable UUID id, @PathVariable Need need) {
            guard.requireSystem(id, need);
            if (!systems.contains(id)) {
                throw new ResourceNotFoundException();
            }
            return Map.of("id", id.toString());
        }

        /** A list: only the Systems the caller's roles allow for the need. */
        @GetMapping("/api/probe/systems")
        @PreAuthorize("isAuthenticated()")
        public List<String> visibleSystems(@RequestParam Need need) {
            var scope = guard.scope(need);
            var visible = new TreeSet<String>();
            systems.stream().filter(scope::contains).forEach(id -> visible.add(id.toString()));
            return List.copyOf(visible);
        }
    }
}
