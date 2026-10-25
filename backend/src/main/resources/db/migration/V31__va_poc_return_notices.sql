-- The notices of a VA POC's return (ticket 35, ADR 0047). Numbered after V30, which rebuilt the CHECK last, because
-- the notification table (V27) does not exist yet when V26 runs on a fresh database.
--
-- VA_POC_REWORK    to the SMO/Tech Lead: a VA POC returned the VA Submission; the REWORK sentence names a Dispute,
--                  which a VA POC's return does not carry
-- VA_POC_RETURNED  to the Cyber Manager who passed it: the resubmission comes back to them
ALTER TABLE notification DROP CONSTRAINT notification_action_check;
ALTER TABLE notification ADD CONSTRAINT notification_action_check CHECK (action IN ('REVIEW', 'REWORK', 'ENDORSE',
    'SUBMIT_OVERDUE', 'REVIEW_OVERDUE', 'ENDORSE_OVERDUE', 'SYSTEM_MOVED', 'SYSTEM_TRANSFERRED_TO_YOU',
    'SYSTEM_TRANSFERRED_FROM_YOU', 'NOT_ENDORSED', 'VA_POC_REWORK', 'VA_POC_RETURNED'));
