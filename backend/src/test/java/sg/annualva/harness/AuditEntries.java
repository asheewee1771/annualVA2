package sg.annualva.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Reads the audit log through {@code GET /api/audit-entries} the way a VA POC's screen does, for every Seam 1 test. The
 * log is the whole suite's and append-only, so a test narrows what it reads (by System, actor, action or business date)
 * and never depends on how many entries the other tests left.
 */
public final class AuditEntries {

    /** The business time zone (ADR 0006), in which the log's {@code from} and {@code to} filters are dates. */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Singapore");

    /** The entry a read of the log is (Q11). */
    private static final String VIEWED = "AUDIT_LOG_VIEWED";

    private AuditEntries() {}

    /**
     * Every entry the query answers, newest first, each once. Each page read is itself a new newest entry
     * ({@code AUDIT_LOG_VIEWED}, ADR 0057), which shifts the next page by one: entries seen twice are kept once, and the
     * reads of the log are left out unless the query asks for that action, so reading never changes what is counted.
     */
    public static List<JsonNode> all(ApiClient reader, String query) {
        boolean readsAsked = query.contains("action=" + VIEWED);
        Map<String, JsonNode> items = new LinkedHashMap<>();
        for (int page = 0;; page++) {
            var response = reader.get("/api/audit-entries?size=200&page=" + page + "&" + query);
            assertThat(response.status()).as(response.body()).isEqualTo(200);
            JsonNode body = ApiClient.json(response);
            for (JsonNode entry : body.get("items")) {
                if (readsAsked || !VIEWED.equals(entry.get("action").asString())) {
                    items.putIfAbsent(entry.get("id").asString(), entry);
                }
            }
            if ((long) (page + 1) * 200 >= body.get("total").asLong()) {
                return new ArrayList<>(items.values());
            }
        }
    }

    /** A filter up to the clock's business date: an entry another test wrote on a later date is never read as newest. */
    public static String untilToday(Clock clock) {
        return "&to=" + LocalDate.ofInstant(clock.instant(), BUSINESS_ZONE);
    }
}
