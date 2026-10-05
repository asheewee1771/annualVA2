package sg.annualva.access;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Step-up rules (prefix {@code annualva.step-up}, IM8 ac-2, ADR 0004).
 *
 * @param freshnessWindow a sensitive action needs an {@code auth_time} no older than this
 * @param clockSkew how far before the moment a step-up started the identity provider's {@code auth_time}
 *     may fall and still count as that step-up's sign-in (the two clocks are not the same clock)
 * @param mode how a fresh sign-in is demanded; {@code PROMPT_LOGIN} sends {@code prompt=login} and
 *     {@code max_age=0}, {@code FULL_SIGN_IN} is the fallback for an identity provider that ignores them
 */
@ConfigurationProperties("annualva.step-up")
@Validated
public record StepUpProperties(
        @DefaultValue("5m") @NotNull Duration freshnessWindow,
        @DefaultValue("30s") @NotNull Duration clockSkew,
        @DefaultValue("PROMPT_LOGIN") @NotNull Mode mode) {

    public enum Mode {
        PROMPT_LOGIN,
        FULL_SIGN_IN
    }
}
