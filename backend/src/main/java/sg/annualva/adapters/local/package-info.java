/**
 * The boundary adapters of the {@code dev-local} profile (ticket 63, ADR 0058): a file store on a git-ignored local folder,
 * a malware scanner that follows mock-SFS's keyword rule, a mail relay that logs instead of sending and an account status
 * that reports everyone enabled. They let one machine with only PostgreSQL run a whole VA Submission. Every class here is
 * annotated {@code @Profile("dev-local")} (an architecture test fails the build otherwise), so none of them exists under
 * {@code ci}, where the Seam 1 harness fakes are the only ones, or in any deployed profile. Depends on boundary only.
 */
package sg.annualva.adapters.local;
