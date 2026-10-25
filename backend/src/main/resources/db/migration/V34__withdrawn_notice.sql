-- The notice of a withdrawal (ticket 43, story 94, ADR 0049): a Register apply retired a System and its in-flight VA
-- Submission became Withdrawn, so its SMO/Tech Lead is told, in the app and by mail with the REGISTER_CHANGE template
-- (V28 already allows it). Numbered after V33, which rebuilt the CHECK last; it lists every action allowed before.
--
-- WITHDRAWN  to the SMO/Tech Lead the Register last named for the retired System: nothing more is due
ALTER TABLE notification DROP CONSTRAINT notification_action_check;
ALTER TABLE notification ADD CONSTRAINT notification_action_check CHECK (action IN ('REVIEW', 'REWORK', 'ENDORSE',
    'SUBMIT_OVERDUE', 'REVIEW_OVERDUE', 'ENDORSE_OVERDUE', 'SYSTEM_MOVED', 'SYSTEM_TRANSFERRED_TO_YOU',
    'SYSTEM_TRANSFERRED_FROM_YOU', 'NOT_ENDORSED', 'VA_POC_REWORK', 'VA_POC_RETURNED', 'DECIDE_EXEMPTION',
    'EXEMPTION_APPROVED', 'EXEMPTION_REJECTED', 'WITHDRAWN'));
