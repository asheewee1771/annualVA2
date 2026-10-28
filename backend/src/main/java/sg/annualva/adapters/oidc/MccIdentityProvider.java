package sg.annualva.adapters.oidc;

import org.springframework.context.annotation.Profile;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import sg.annualva.boundary.IdentityProvider;

/**
 * MCC SSO claim shapes: the person's identifier is {@code afcas.uuid}, carried either as a nested
 * object ({@code "afcas": {"uuid": ...}}) or as a flat claim named {@code "afcas.uuid"}; the account's status sits beside
 * it in the same two shapes.
 */
@Component
@Profile("!dev-local")
class MccIdentityProvider implements IdentityProvider {

    private static final String AFCAS = "afcas";
    private static final String UUID = "uuid";
    private static final String FLAT_AFCAS_UUID = "afcas.uuid";
    private static final String STATUS = "status";
    private static final String FLAT_AFCAS_STATUS = "afcas.status";
    private static final String ACTIVE = "active";

    @Override
    public Identity identityFrom(Map<String, Object> claims) {
        return new Identity(
                ssoId(claims),
                string(claims.get("email")),
                Boolean.TRUE.equals(claims.get("email_verified")),
                displayName(claims),
                claims.get("auth_time") instanceof Instant authTime ? authTime : null,
                amr(claims.get("amr")),
                string(claims.get("acr")),
                disabled(claims));
    }

    private static @Nullable String ssoId(Map<String, Object> claims) {
        return afcas(claims, UUID, FLAT_AFCAS_UUID);
    }

    /**
     * Provisional until the H4 onboarding confirms MCC SSO's claim (ADR 0056): the account's status, nested
     * ({@code "afcas": {"status": ...}}) or flat ({@code "afcas.status"}). Absent means enabled, because a token without it
     * says nothing; any value but {@code active} means disabled, so a new status the provider adds fails closed.
     */
    private static boolean disabled(Map<String, Object> claims) {
        String status = afcas(claims, STATUS, FLAT_AFCAS_STATUS);
        return status != null && !ACTIVE.equalsIgnoreCase(status);
    }

    private static @Nullable String afcas(Map<String, Object> claims, String nested, String flat) {
        if (claims.get(AFCAS) instanceof Map<?, ?> afcas && afcas.get(nested) instanceof String value && !value.isBlank()) {
            return value;
        }
        return string(claims.get(flat));
    }

    private static @Nullable String displayName(Map<String, Object> claims) {
        String name = string(claims.get("name"));
        return name != null ? name : string(claims.get("preferred_username"));
    }

    private static List<String> amr(@Nullable Object value) {
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private static @Nullable String string(@Nullable Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
