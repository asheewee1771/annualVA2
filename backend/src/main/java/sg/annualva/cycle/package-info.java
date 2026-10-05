/**
 * The VA Cycle and Quarter calendar (story 16, the dates of story 71): which VA Cycle and Quarter today falls in, each
 * Quarter's Submission, Review and Endorsement Windows and due dates, the Cycle's default close date, and which
 * Quarters are Handled outside the app. It is a pure function of the year, the {@code annualva.calendar} setting and
 * the {@link java.time.Clock} bean, computed in the business zone (ADR 0006); nothing is stored (ADR 0015). Read
 * through {@code GET /api/cycles} and {@code GET /api/cycles/{year}}. Depends on config and web only.
 */
package sg.annualva.cycle;
