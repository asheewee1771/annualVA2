package sg.annualva.monitoring;

/**
 * Whether a scheduled job's pass did its work, for the job's last-completed-run signal (ticket 67, ADR 0060 item 8): it
 * did unless it had work and none of it went through. One instance per pass, whatever steps the pass has, on one thread:
 * the pass counts each piece of work it tried and whether that piece went through.
 */
public final class PassTally {

    private int tried;
    private int failed;

    /** Counts one piece of work, and whether it went through. */
    public void add(boolean wentThrough) {
        tried++;
        if (!wentThrough) {
            failed++;
        }
    }

    /** True unless the pass had work and none of it went through. */
    public boolean didItsWork() {
        return tried == 0 || failed < tried;
    }

    /** How many pieces of work the pass tried. */
    public int tried() {
        return tried;
    }

    /** How many of them did not go through. */
    public int failed() {
        return failed;
    }
}
