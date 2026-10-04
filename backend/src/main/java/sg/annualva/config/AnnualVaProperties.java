package sg.annualva.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application-wide settings (prefix {@code annualva}). Module-specific settings live in their own
 * records inside the owning module.
 *
 * @param timeZone business zone for VA Cycle, Quarter and Window arithmetic (timestamps are stored in UTC)
 * @param classification the IM8 dp-8 labels
 */
@ConfigurationProperties("annualva")
@Validated
public record AnnualVaProperties(@NotBlank String timeZone, Classification classification) {

    /** @param banner text of the classification banner shown on every screen */
    public record Classification(@NotBlank String banner) {}
}
