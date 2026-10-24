package sg.annualva.ingestion;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.submission.ExemptionEvidence;

/**
 * An Exemption's evidence file is a supporting evidence file of the same VA Submission that has passed every stage of
 * the pipeline (READY, ADR 0026): it came in through the one upload path, was scanned and type-checked, and is served
 * only as an attachment, like every file (ticket 40).
 */
@Component
class ReadyEvidence implements ExemptionEvidence {

    private final ReportFileStore store;

    ReadyEvidence(ReportFileStore store) {
        this.store = store;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isReadyEvidence(UUID submissionId, UUID fileId) {
        return store.find(fileId).filter(file -> file.submissionId().equals(submissionId))
                .filter(file -> file.kind() == FileKind.EVIDENCE && file.status() == PipelineStatus.READY)
                .isPresent();
    }
}
