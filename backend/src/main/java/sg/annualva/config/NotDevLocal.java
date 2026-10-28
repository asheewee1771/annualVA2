package sg.annualva.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;

/**
 * Exists in every profile but {@code dev-local} (ticket 63, ADR 0058): the single sign-on client and the stubs of the
 * boundary adapters that ticket 51 replaces. Under {@code dev-local} there is no identity provider to register or
 * discover, and the local adapters take the stubs' place. An architecture test fails the build when a bean of
 * {@code adapters.oidc} or {@code adapters.pending} does not carry it, so a new one cannot be forgotten.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile("!" + DevLocal.PROFILE)
public @interface NotDevLocal {}
