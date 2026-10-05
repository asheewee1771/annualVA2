package sg.annualva.access;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A person known to the application: pre-registered by email (System Register) or linked to an
 * identity-provider id at first sign-in.
 */
public record AppUser(UUID id, String email, @Nullable String ssoId, @Nullable String displayName) {}
