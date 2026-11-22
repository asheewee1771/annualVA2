package sg.annualva.web;

import static org.assertj.core.api.Assertions.assertThat;
import static sg.annualva.harness.Metrics.AUDIT_WRITE_FAILURES;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import sg.annualva.harness.ApiClient;
import sg.annualva.harness.Metrics;
import sg.annualva.harness.Seam1Test;

/**
 * IM8 lm-16 (ticket 52, ADR 0060): an audit entry that cannot be written is counted once. In the action's transaction the
 * action is not done; in a transaction of its own (a refusal), a failure anywhere in that transaction, its commit
 * included, is counted, and the request is still refused.
 */
class AuditFailureSignalTest extends Seam1Test {

    @AfterEach
    void faultOff() {
        arrange.auditFault("off");
    }

    @Test
    void anAuditEntryTheDatabaseRefusesIsCountedOnceAndTheActionIsNotDone() {
        ApiClient poc = freshVaPoc();
        Metrics before = metrics();
        arrange.auditFault("on");

        var response = poc.postJson(probe("commit"), "");

        assertThat(response.status()).isEqualTo(500);
        assertThat(metrics().since(before, AUDIT_WRITE_FAILURES)).isEqualTo(1);
    }

    @Test
    void aWrittenEntryIsNotCounted() {
        ApiClient poc = freshVaPoc();
        Metrics before = metrics();

        assertThat(poc.postJson(probe("commit"), "").status()).isEqualTo(200);

        assertThat(metrics().since(before, AUDIT_WRITE_FAILURES)).isZero();
    }

    @Test
    void aRefusalWhoseOwnAuditTransactionFailsToCommitIsCountedOnceAndStillRefused() {
        Metrics before = metrics();
        arrange.auditFault("commit");

        var refused = api.get("/api/systems");

        assertThat(refused.status()).isEqualTo(401);
        assertThat(metrics().since(before, AUDIT_WRITE_FAILURES)).isEqualTo(1);
    }

    private static String probe(String ending) {
        return "/api/probe/audited?system=" + UUID.randomUUID() + "&submission=" + UUID.randomUUID() + "&finding="
                + UUID.randomUUID() + "&ending=" + ending;
    }
}
