package sg.annualva.scan;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How the malware scan is driven (prefix {@code annualva.scan}). The defaults are the production values.
 *
 * @param attemptTimeout how long one scan attempt of a Scan Report file may take before it is submitted again (the
 *     file is rejected after the third attempt); the application clock measures it
 * @param pollInterval how long the scan job waits between passes; the job reads the same key (its placeholder default is
 *     this one)
 * @param maxInFlight the most files out with the scanner at once
 * @param syncWait how long the System Register upload waits for one scan attempt: the file is small and the VA POC
 *     waits for the answer (ADR 0017)
 * @param syncPollInterval how often that wait asks the scanner for the verdict
 */
@ConfigurationProperties("annualva.scan")
@Validated
public record ScanProperties(
        @DefaultValue("10m") @NotNull Duration attemptTimeout,
        @DefaultValue("10s") @NotNull Duration pollInterval,
        @DefaultValue("10") @Min(1) int maxInFlight,
        @DefaultValue("15s") @NotNull Duration syncWait,
        @DefaultValue("250ms") @NotNull Duration syncPollInterval) {}
