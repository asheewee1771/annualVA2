package sg.annualva.app;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The monitoring endpoint's credential (prefix {@code annualva.monitoring}, IM8 lm-16, ADR 0060).
 *
 * @param scrapeToken the bearer token the metrics scraper sends to {@code /actuator/prometheus}: 32 to 200 visible
 *     characters, no space. It has no default, so an environment that does not set it does not start, rather than expose
 *     the endpoint open or never scrape it. A secret: set from the environment ({@code ANNUALVA_MONITORING_SCRAPE_TOKEN}),
 *     and left out of {@link #toString()}.
 */
@ConfigurationProperties("annualva.monitoring")
@Validated
public record MonitoringProperties(
        @NotBlank @Pattern(regexp = "[\\x21-\\x7E]{32,200}",
                message = "must be 32 to 200 visible characters with no space") String scrapeToken) {

    @Override
    public String toString() {
        return "MonitoringProperties[scrapeToken=set]";
    }
}
