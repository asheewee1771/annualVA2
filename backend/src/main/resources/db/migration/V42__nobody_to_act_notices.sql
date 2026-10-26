-- A notice to a System's SMO/Tech Lead or Cyber Manager whose role for it is suspended or lapsed goes to both VA POCs
-- instead, as a pair, naming the step nobody can act at (decided 2026-10-26, docs/agents/reviewer-decisions.md;
-- ADR 0043, 0046, 0052). The pair clears when a VA POC restores the role or applies a Register naming someone else.
-- Rebuilds the two CHECKs V38 and V37 rebuilt last, with every value allowed before plus the new ones.
ALTER TABLE notification DROP CONSTRAINT notification_action_check;
ALTER TABLE notification ADD CONSTRAINT notification_action_check CHECK (action IN ('REVIEW', 'REWORK', 'ENDORSE',
    'SUBMIT_OVERDUE', 'REVIEW_OVERDUE', 'ENDORSE_OVERDUE', 'SYSTEM_MOVED', 'SYSTEM_TRANSFERRED_TO_YOU',
    'SYSTEM_TRANSFERRED_FROM_YOU', 'NOT_ENDORSED', 'VA_POC_REWORK', 'VA_POC_RETURNED', 'DECIDE_EXEMPTION',
    'EXEMPTION_APPROVED', 'EXEMPTION_REJECTED', 'WITHDRAWN', 'CONFIRM_VA', 'VA_CONFIRMED', 'EXEMPTION_LAPSED',
    'EXEMPTION_LAPSED_NOT_ENDORSED', 'SUBMIT_DUE_SOON', 'SUBMIT_DUE_TODAY',
    'SUBMIT_STILL_OVERDUE', 'SUBMIT_OVERDUE_COPY', 'REVIEW_DUE_SOON', 'REVIEW_DUE_TODAY', 'REVIEW_STILL_OVERDUE',
    'REVIEW_OVERDUE_COPY', 'ENDORSE_DUE_IN_TWO_WEEKS', 'ENDORSE_DUE_IN_ONE_WEEK', 'NOBODY_TO_SUBMIT',
    'NOBODY_TO_REVIEW'));

ALTER TABLE notification DROP CONSTRAINT notification_awaits_check;
ALTER TABLE notification ADD CONSTRAINT notification_awaits_check CHECK (awaits IN ('ENDORSEMENT', 'EXEMPTION',
    'VA_CONFIRMATION', 'ROLE_HOLDER'));

-- An open pair about a System and a step nobody can act at is found by its System, not its VA Submission.
CREATE INDEX notification_open_role_holder_idx ON notification (system_id, action)
    WHERE handled_at IS NULL AND awaits = 'ROLE_HOLDER';
