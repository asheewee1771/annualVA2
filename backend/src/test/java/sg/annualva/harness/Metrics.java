package sg.annualva.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

/**
 * The key signals as the monitoring system's scraper reads them (ticket 52): one GET of {@code /actuator/prometheus} with
 * the CI profile's bearer token, parsed into series. Counters are cumulative over every test that shares the application
 * context, so a test reads before and after its action and compares the two.
 */
public final class Metrics {

    /** The CI profile's {@code annualva.monitoring.scrape-token}. */
    public static final String TOKEN = "ci-scrape-token-0123456789abcdef0123456789";

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

    public boolean has(String series) {
        return this.series.containsKey(series);
    }

    /** Every series name of the scrape, tags included. */
    public Iterable<String> names() {
        return series.keySet();
    }
}
