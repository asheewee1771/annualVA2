-- The VA POC's Cycle matrix (ticket 33, story 64, ADR 0036) shows each VA Submission's three milestones against the day
-- each is due: submitted (submitted_at, V19, due submit_due), reviewed (due review_due) and endorsed (due endorse_due).
-- va_submission learns when the last two were reached:
--   passed_to_endorsement_at  when (UTC) the Cyber Manager last passed it to endorsement, which ends the review; written
--                             by the pass-to-endorsement command (ticket 30)
--   endorsed_at               when (UTC) a VA POC endorsed it; written by the endorse command (ticket 34), beside
--                             endorsed_late
-- Both stay null until those commands arrive. IF NOT EXISTS, because the tickets that write them may add the same
-- columns in their own migrations.
ALTER TABLE va_submission
    ADD COLUMN IF NOT EXISTS passed_to_endorsement_at timestamptz,
    ADD COLUMN IF NOT EXISTS endorsed_at timestamptz;
