package sg.annualva.access;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AbstractAuthenticationProcessingFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.stereotype.Component;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.boundary.IdentityProvider.Identity;
import sg.annualva.web.ProblemResponses;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sign-in as a persona, for a developer's machine without the single sign-on (ticket 63, ADR 0058). {@code POST
 * /api/dev/sign-in} with {@code {"persona": "<afcas.uuid>"}} builds the identity the single sign-on would have asserted
 * (the persona's uuid, no email so that the existing dev identities supply it, an {@code auth_time} of now) and hands it
 * to {@link SignInService}, the one place the sign-in rules live: email to role resolution, the account-status check and
 * the linking of the person to a user. What follows is the filter chain's own: the session strategies (the session id
 * rotates, the person's older session ends, one session per person) run as for any sign-in, and {@link
 * SignInSuccessHandler#establish} writes the session and the audit entry. Nothing here grants a role: the body names a
 * persona and nothing else, and what the persona can do is what the System Register says about its email.
 */
@Component
@Profile("dev-local")
class DevLocalSignIn implements SignInMethod {

    static final String PATH = "/api/dev/sign-in";

    private static final int MAX_BODY_BYTES = 1024;
    private static final String PERSONA = "persona";

    private final DevLocalPersonas personas;
    private final SignInService signIn;
    private final SignInSuccessHandler success;
    private final AccessAudit audit;
    private final Clock clock;
    private final ObjectMapper json;

    DevLocalSignIn(DevLocalPersonas personas, SignInService signIn, SignInSuccessHandler success, AccessAudit audit,
            Clock clock, ObjectMapper json) {
        this.personas = personas;
        this.signIn = signIn;
        this.success = success;
        this.audit = audit;
        this.clock = clock;
        this.json = json;
    }

    @Override
    public void apply(HttpSecurity http) throws Exception {
        // The session strategies exist only once the chain is initialised, so the filter is added when it is configured.
        http.with(new Wiring(), Customizer.withDefaults());
    }

    private final class Wiring extends AbstractHttpConfigurer<Wiring, HttpSecurity> {
        @Override
        public void configure(HttpSecurity http) {
            var filter = new Filter();
            filter.setSessionAuthenticationStrategy(http.getSharedObject(SessionAuthenticationStrategy.class));
            filter.setSecurityContextRepository(http.getSharedObject(SecurityContextRepository.class));
            http.addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class);
        }
    }

    /** A request that does not name one of the six personas. Says nothing of what was sent. */
    private static final class UnknownPersonaException extends AuthenticationException {
        private static final long serialVersionUID = 1L;

        UnknownPersonaException() {
            super("Not one of the dev-local personas");
        }
    }

    private final class Filter extends AbstractAuthenticationProcessingFilter {

        Filter() {
            super(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, PATH));
            setAuthenticationSuccessHandler((request, response, authentication) -> {
                success.establish(request, response, ((SessionAuthentication) authentication).getPrincipal());
                response.setStatus(HttpStatus.NO_CONTENT.value());
            });
            setAuthenticationFailureHandler(this::refuse);
        }

        @Override
        public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response)
                throws AuthenticationException {
            DevLocalPersonas.Persona persona = personas.find(requestedPersona(request))
                    .orElseThrow(UnknownPersonaException::new);
            var identity = new Identity(persona.id(), null, false, persona.name(), clock.instant(), List.of(), null,
                    false);
            return new SessionAuthentication(signIn.signIn(identity));
        }

        private String requestedPersona(HttpServletRequest request) {
            JsonNode body = null;
            try {
                byte[] bytes = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
                body = bytes.length > MAX_BODY_BYTES ? null : json.readTree(bytes);
            } catch (IOException | RuntimeException notJson) {
                // Anything that is not a small JSON object naming a persona is the same refusal, whatever it was.
            }
            JsonNode persona = body != null && body.isObject() ? body.get(PERSONA) : null;
            if (persona == null || !persona.isString()) {
                throw new UnknownPersonaException();
            }
            return persona.asString();
        }

        private void refuse(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure)
                throws IOException {
            ProblemDetail problem;
            if (failure instanceof SignInRefusedException refused) {
                audit.signInFailed(refused.reason(), "other", AuditOutcome.REFUSED);
                problem = ProblemResponses.problem(HttpStatus.FORBIDDEN, "sign-in-refused", "The sign-in was refused.");
                problem.setProperty("reason", refused.reason());
            } else if (failure instanceof UnknownPersonaException) {
                problem = ProblemResponses.problem(HttpStatus.BAD_REQUEST, "unknown-persona",
                        "The request must name one of the personas listed at /api/dev/personas.");
            } else {
                throw new AuthenticationServiceException("The persona sign-in failed", failure);
            }
            response.setStatus(problem.getStatus());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setHeader("Cache-Control", "no-store");
            json.writeValue(response.getOutputStream(), problem);
        }
    }
}
