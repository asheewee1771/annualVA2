package sg.annualva.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How mail goes out through the relay (prefix {@code annualva.mail}, ADR 0044). The defaults are the production values;
 * the application refuses to start without the app's address or a template id for every {@link MailTemplate}.
 *
 * @param appUrl the address a mailed link starts with (https, no trailing slash)
 * @param templates the relay's id of each logical template, as onboarded with the notification service (ticket 57)
 * @param maxAttempts how many times a mail is handed to the relay before it is given up on
 * @param firstRetry how long after a first failed attempt the next one is made; each later wait is twice the one before
 * @param lease how long a claimed mail is left alone before another pass may claim it again (a dispatcher that stopped
 *     part-way); a mail is leased just before its hand-over, so this must be longer than one relay call can take (the
 *     relay adapter's timeout, ticket 51)
 * @param batchSize the most mails one pass hands over
 * @param dispatchInterval how long the job waits between passes; the job reads the same key
 */
@ConfigurationProperties("annualva.mail")
@Validated
public record MailProperties(
        @NotNull @Pattern(regexp = "https://[a-z0-9.-]+(:[0-9]{1,5})?") String appUrl,
        @NotNull Map<MailTemplate, String> templates,
        @DefaultValue("5") @Min(1) @Max(10) int maxAttempts,
        @DefaultValue("1m") @NotNull Duration firstRetry,
        @DefaultValue("5m") @NotNull Duration lease,
        @DefaultValue("50") @Min(1) int batchSize,
        @DefaultValue("30s") @NotNull Duration dispatchInterval) {

    public MailProperties {
        // Bound before validation runs: no templates at all is reported like a missing one.
        templates = templates == null ? Map.of() : templates;
        EnumSet<MailTemplate> missing = EnumSet.allOf(MailTemplate.class);
        templates.forEach((template, id) -> {
            if (id != null && !id.isBlank()) {
                missing.remove(template);
            }
        });
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("annualva.mail.templates has no relay template id for " + missing);
        }
        templates = Map.copyOf(templates);
    }

    /** The relay's id of a logical template. */
    String templateId(MailTemplate template) {
        return templates.get(template);
    }
}
