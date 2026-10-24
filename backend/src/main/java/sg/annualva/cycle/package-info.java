/**
 * The VA Cycle and Quarter calendar (story 16, the dates of story 71): which VA Cycle and Quarter today falls in, each
 * Quarter's Submission, Review and Endorsement Windows and due dates, the Cycle's default close date, and which
 * Quarters are Handled outside the app. It is computed from the year, the {@code annualva.calendar} setting and
 * the {@link java.time.Clock} bean, computed in the business zone (ADR 0006), and stores only what cannot be derived: that
 * a Cycle opened and when it closed, with an early close's date ({@link sg.annualva.cycle.CycleStore}, ADR 0015, ADR
 * 0044). Read through {@code GET /api/cycles} and {@code GET /api/cycles/{year}}, which say which Cycles are open.
 * Depends on config and web only.
 */
package sg.annualva.cycle;
