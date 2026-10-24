-- A VA POC's return (ticket 35, stories 66 and 63, ADR 0046): a VA POC returns a VA Submission Awaiting Endorsement to
-- its SMO/Tech Lead with a comment, to challenge something the Cyber Manager confirmed. The return is kept as the Cyber
-- Manager's is (V130), at the endorsing step, and attaches nothing: nothing is Disputed Awaiting Endorsement.
--
-- submission_return.step  REVIEW (the Cyber Manager, V130) or ENDORSE (a VA POC); a VA POC's return always carries a
--                         comment, which the application requires, as nothing else explains it
ALTER TABLE submission_return DROP CONSTRAINT submission_return_step_check;
ALTER TABLE submission_return ADD CONSTRAINT submission_return_step_check
    CHECK (step IN ('REVIEW', 'ENDORSE') AND (step = 'REVIEW' OR comment IS NOT NULL));
