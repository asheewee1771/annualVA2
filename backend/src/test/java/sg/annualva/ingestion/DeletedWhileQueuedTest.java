package sg.annualva.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import sg.annualva.boundary.FileStore;

/**
 * Ticket 68's one deliberate change in behaviour (ADR 0061 item 8): the scan job reads each file it takes from its queue
 * as a live report file, so a file the SMO/Tech Lead deleted after the job read its queue, and before it read the file,
 * is not handed to the malware scanner. The fake scanner's hook deletes the second file through the API while the job is
 * submitting the first, which falls between the job's read of the queue and its read of the second file.
 */
class DeletedWhileQueuedTest extends DeleteSeam1 {

    @Test
    void aFileDeletedAfterTheJobReadItsQueueIsNotSentToTheScanner() {
        Draft draft = draft();
        String sub = draft.submissionId();
        String kept = uploaded(draft.smo(), sub, DEPENDENCY_CHECK, "kept.json", firstScan()).get("id").asString();
        String mistake = uploaded(draft.smo(), sub, DEPENDENCY_CHECK, "mistake.json", firstScan()).get("id").asString();
        scanner.beforeNextSubmit(() -> fileDeleted(draft.smo(), sub, mistake));

        pass();

        assertThat(scanner.submittedNames()).as("the deleted file is not sent, the other one is")
                .containsExactly("kept.json");
        assertThat(status(draft.smo(), sub, kept).get("status").asString()).isEqualTo("SCAN_SUBMITTED");
        assertThat(fileIds(draft.smo(), sub)).containsExactly(kept);

        pass();
        pass();

        assertThat(scanner.submittedNames()).as("nor in a later pass").containsExactly("kept.json");
        assertThat(fileStore.count(FileStore.Zone.QUARANTINE)).as("the deleted file's unscanned bytes are deleted")
                .isZero();
    }
}
