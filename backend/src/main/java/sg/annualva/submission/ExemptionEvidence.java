package sg.annualva.submission;

import java.util.UUID;

/**
 * Whether a file can stand as an Exemption's evidence file (ticket 40): a supporting evidence file of the same VA
 * Submission that passed every stage of the upload pipeline (READY, ADR 0026). The files are kept by the module above this
 * one, which implements it, so the request reads it under its lock without reaching up.
 */
public interface ExemptionEvidence {

    /** True for a READY supporting evidence file of this VA Submission. Must run in the caller's transaction. */
    boolean isReadyEvidence(UUID submissionId, UUID fileId);
}
