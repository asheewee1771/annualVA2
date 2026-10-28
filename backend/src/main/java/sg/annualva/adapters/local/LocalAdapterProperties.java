package sg.annualva.adapters.local;

import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import sg.annualva.config.DevLocal;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the local adapters (prefix {@code annualva.dev-local}); only under the {@code dev-local} profile.
 *
 * @param fileStoreDir where the file store keeps its zones, one sub-folder each; relative to the folder the application
 *     starts in, so {@code backend/.dev-local/files} when started from {@code backend/} (git-ignored)
 * @param scanDelay how long a scan takes, so a screen that waits on a scan can be seen waiting; zero finishes at once
 */
@ConfigurationProperties("annualva.dev-local")
@Validated
@DevLocal
public record LocalAdapterProperties(
        @DefaultValue(".dev-local/files") @NotNull Path fileStoreDir,
        @DefaultValue("1s") @NotNull Duration scanDelay) {}
