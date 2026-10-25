package sg.annualva.retention;

/** Why an item is due: the retention rule that has run out for it (spec "Retention"). */
public enum DisposalReason {
    /** Its VA Cycle closed more than 3 years ago. */
    CYCLE_RETENTION_ENDED,
    /** A superseded file whose VA Submission is Endorsed. */
    SUPERSEDED_AFTER_ENDORSEMENT,
    /** An export whose 24 hours are up. */
    EXPORT_EXPIRED,
    /** A retired System with nothing left that its Cycles still keep. */
    SYSTEM_RETIRED
}
