package sg.annualva.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The key signals as the monitoring system's scraper reads them (ticket 52): one GET of {@code /actuator/prometheus} with
 * the CI profile's bearer token, parsed into series. Counters are cumulative over every test that shares the application
 * context, so a test reads before and after its action and compares the two ({@link #since}).
 */
public final class Metrics {

    /** The CI profile's {@code annualva.monitoring.scrape-token} ({@code application-ci.yml}; change both together). */
    public static final String TOKEN = "ci-scrape-token-0123456789abcdef0123456789";

    public static final String SCAN_FAILURES = "annualva_scan_failures_total";
    public static final String SCAN_TIMEOUTS = "annualva_scan_timeouts_total";
    public static final String AUDIT_WRITE_FAILURES = "annualva_audit_write_failures_total";
    public static final String MAIL_SEND_FAILED = mail("send_failed");
    public static final String MAIL_ABANDONED = mail("abandoned");
    public static final String MAIL_RECORD_FAILED = mail("record_failed");
    public static final String MAIL_REFUSED = mail("refused");
    public static final String MAIL_ADDRESS_REJECTED = mail("address_rejected");
    public static final String STEP_UP_REQUIRED = "annualva_stepup_refusals_total{kind=\"required\"}";
    public static final String STEP_UP_FAILED = "annualva_stepup_refusals_total{kind=\"failed\"}";
    public static final String OVERDUE_SUBMISSIONS = "annualva_submissions_overdue{kind=\"submission\"}";
    public static final String OVERDUE_NOT_STARTED = "annualva_submissions_overdue{kind=\"not_started\"}";

    /** Every key signal of ADR 0060, as Prometheus writes it. */
    public static final List<String> KEY_SIGNALS = List.of(SCAN_FAILURES, SCAN_TIMEOUTS, AUDIT_WRITE_FAILURES,
            MAIL_SEND_FAILED, MAIL_ABANDONED, MAIL_RECORD_FAILED, MAIL_REFUSED, MAIL_ADDRESS_REJECTED, STEP_UP_REQUIRED,
            STEP_UP_FAILED, OVERDUE_SUBMISSIONS, OVERDUE_NOT_STARTED);

    /** Every kind of mail failure. */
    public static final List<String> MAIL_FAILURES =
            List.of(MAIL_SEND_FAILED, MAIL_ABANDONED, MAIL_RECORD_FAILED, MAIL_REFUSED, MAIL_ADDRESS_REJECTED);

    private final Map<String, Double> series = new HashMap<>();

    private Metrics(String text) {
        for (String line : text.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int space = line.lastIndexOf(' ');
            series.put(line.substring(0, space), Double.parseDouble(line.substring(space + 1)));
        }
    }

    private static String mail(String kind) {
        return "annualva_mail_failures_total{kind=\"" + kind + "\"}";
    }

    /** One scrape, as the scraper does it. */
    public static Metrics scrape(ApiClient client) {
        var response = client.get("/actuator/prometheus", Map.of("Authorization", "Bearer " + TOKEN));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return new Metrics(response.body());
    }

    /**
     * The value of one series, written as Prometheus writes it: {@code annualva_mail_failures_total{kind="send_failed"}}, or
     * a name alone when it has no tag.
     */
    public double value(String series) {
        assertThat(this.series).as("the scrape has the series %s", series).containsKey(series);
        return this.series.get(series);
    }

    /** How much one series moved from an earlier scrape to this one. */
    public double since(Metrics before, String series) {
        return value(series) - before.value(series);
    }

    /** Every series name of the scrape, tags included. */
    public Iterable<String> names() {
        return series.keySet();
    }
}
