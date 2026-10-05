package sg.annualva.cycle;

/** Where a VA Cycle stands on a business day. */
public enum CyclePhase {
    /** Its Q1 has not started. */
    UPCOMING,
    /** Today is inside one of its Quarters. */
    RUNNING,
    /** Q4 has ended and the Cycle has not reached its close date: late Q4 endorsements still have time. */
    CLOSING,
    /** Past the close date. */
    CLOSED
}
