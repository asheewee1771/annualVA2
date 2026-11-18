package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Metrics;
import sg.annualva.harness.Seam1Test;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): an audit entry that cannot be written is counted, and the action it described is not
 * done (the entry is written in the action's transaction).
 */
class AuditFailureSignalTest extends Seam1Test {

    private static final String WRITES_FAILED = "annualva_audit_write_failures_total";

    @AfterEach
    void faultOff() {
        arrange.auditFault("off");
    }

    @Test
    void anAuditEntryTheDatabaseRefusesIsCountedOnceAndTheActionIsNotDone() {
        ApiClient poc = freshVaPoc();
        double before = metrics().value(WRITES_FAILED);
        arrange.auditFault("on");

        var response = poc.postJson(probe("commit"), "");

        assertThat(response.status()).isEqualTo(500);
        assertThat(metrics().value(WRITES_FAILED)).isEqualTo(before + 1);
    }

    @Test
    void aWrittenEntryIsNotCounted() {
        ApiClient poc = freshVaPoc();
        double before = metrics().value(WRITES_FAILED);

        assertThat(poc.postJson(probe("commit"), "").status()).isEqualTo(200);

        assertThat(metrics().value(WRITES_FAILED)).isEqualTo(before);
    }

    private static String probe(String ending) {
        return "/api/probe/audited?system=" + UUID.randomUUID() + "&submission=" + UUID.randomUUID() + "&finding="
                + UUID.randomUUID() + "&ending=" + ending;
    }
}
