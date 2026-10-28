package sg.annualva.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Profile;

/**
 * Exists only under the {@code dev-local} profile (ticket 63, ADR 0058): the persona sign-in and the local boundary
 * adapters of a developer's machine. The one place the profile's name is written for the classes that belong to it; an
 * architecture test fails the build when such a class does not carry it.
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Profile(DevLocal.PROFILE)
public @interface DevLocal {

    /** The profile's name. */
    String PROFILE = "dev-local";
}
