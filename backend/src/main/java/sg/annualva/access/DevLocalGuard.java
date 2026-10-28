package sg.annualva.access;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import sg.annualva.config.DevLocal;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * The start-up guard of the {@code dev-local} profile (ticket 63, ADR 0058): anyone who can reach a running dev-local
 * application can sign in as any persona, so it must be impossible to run one anywhere but a developer's machine. The
 * context fails to start unless the profile stands alone (no {@code sit}, {@code ci}, {@code dev-mcc} or any other
 * profile, so a production profile name cannot slip through a deny-list), the server listens on a loopback address
 * only (an unset address listens on every interface, so it is refused too; so is a management server with a port of its
 * own and no loopback address) and the database, and Flyway's if it has an address of its own, is on this machine. It is
 * created even when the application starts lazily. It runs while the beans are created, not before the first of them, so
 * the profile files supply only local database addresses (ADR 0058 item 6).
 */
@Component
@Lazy(false)
@DevLocal
class DevLocalGuard {

    static final String PROFILE = DevLocal.PROFILE;

    private static final Logger log = LoggerFactory.getLogger(DevLocalGuard.class);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*");

    DevLocalGuard(Environment environment, JdbcConnectionDetails database) {
        List<String> others = Arrays.stream(environment.getActiveProfiles()).filter(profile -> !PROFILE.equals(profile))
                .toList();
        if (!others.isEmpty()) {
            throw new IllegalStateException("The profile dev-local lets anyone sign in as any persona and may not run with "
                    + "another profile; refusing to start with " + String.join(", ", others));
        }
        String address = environment.getProperty("server.address");
        if (address == null || !loopback(address)) {
            throw new IllegalStateException("The profile dev-local needs server.address to be a loopback address "
                    + "(127.0.0.1 or ::1), so that only this machine can reach it");
        }
        String managementPort = environment.getProperty("management.server.port");
        String managementAddress = environment.getProperty("management.server.address");
        if (managementPort != null && !managementPort.isBlank()
                && (managementAddress == null || !loopback(managementAddress))) {
            throw new IllegalStateException("The profile dev-local needs management.server.address to be a loopback "
                    + "address when the management server has a port of its own");
        }
        String flywayUrl = environment.getProperty("spring.flyway.url");
        if (!databaseOnThisMachine(database.getJdbcUrl())
                || (flywayUrl != null && !flywayUrl.isBlank() && !databaseOnThisMachine(flywayUrl))) {
            throw new IllegalStateException("The profile dev-local needs the database on this machine (localhost, "
                    + "127.0.0.1 or ::1)");
        }
        log.atWarn()
                .setMessage("The dev-local profile is active: anyone who can reach this application can sign in as any "
                        + "persona, and its files, scans, mail and account checks are local fakes. Never run it anywhere "
                        + "but a developer's machine.")
                .addKeyValue("event.action", "dev-local-active")
                .addKeyValue("event.category", "configuration")
                .log();
    }

    /** A literal loopback address or {@code localhost}; any other name is not looked up, so it is refused. */
    private static boolean loopback(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if ("localhost".equalsIgnoreCase(bare)) {
            return true;
        }
        if (!IPV4.matcher(bare).matches() && !IPV6.matcher(bare).matches()) {
            return false;
        }
        try {
            return InetAddress.getByName(bare).isLoopbackAddress();
        } catch (UnknownHostException notAnAddress) {
            return false;
        }
    }

    private static boolean databaseOnThisMachine(String jdbcUrl) {
        try {
            String host = new URI(jdbcUrl.startsWith("jdbc:") ? jdbcUrl.substring("jdbc:".length()) : jdbcUrl).getHost();
            return host != null && loopback(host);
        } catch (URISyntaxException notAUrl) {
            return false;
        }
    }
}
