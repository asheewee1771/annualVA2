-- Exemptions (ticket 40, stories 43 to 46, ADR 0048): an SMO/Tech Lead requests one for their System with evidence of a
-- VA in the 3 months before its Quarter (PRIOR_VA) or one scheduled in the 3 months after (SCHEDULED_VA); the VA
-- Submission is frozen in Exemption Requested until a VA POC approves it (Exempted) or rejects it (back to exactly the
-- state it was in).
--
-- Numbered 144.40, not V140: it rebuilds CHECKs that V141 (ticket 41) and V144 (ticket 44), merged first, rebuilt, so it
-- must run after them.
--
-- exemption: one row per request; the request's columns are never changed, the decision's are written once.
--   submission_id          the VA Submission it freezes; a request on a Not Started System creates it (state NOT_STARTED)
--   basis, va_date         the kind of VA and its business date (Asia/Singapore), checked against the Quarter's windows
--   evidence               what the SMO/Tech Lead wrote: cleaned by the one text rule, never logged or audited
--   evidence_file_id       an optional supporting evidence file of the same VA Submission, READY in the upload pipeline
--   previous_state         the state a rejection restores
--   previous_overdue_since the Overdue flag's time when it froze (the freeze clears it: a frozen state waits on no window);
--                          a rejection restores it if the restored state's window has still passed
--   decision               APPROVED or REJECTED; null while pending. At most one pending request per VA Submission
--   lapses_on              for an approved SCHEDULED_VA (a conditional Exemption): the Quarter's last day plus 3 months,
--                          by which the VA must be recorded (ticket 42)
CREATE TABLE exemption (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq                    bigint      GENERATED ALWAYS AS IDENTITY,
    submission_id          uuid        NOT NULL REFERENCES va_submission (id),
    basis                  text        NOT NULL,
    va_date                date        NOT NULL,
    evidence               text        NOT NULL,
    evidence_file_id       uuid        REFERENCES report_file (id),
    previous_state         text        NOT NULL,
    previous_overdue_since timestamptz,
    requested_by           uuid        NOT NULL REFERENCES app_user (id),
    requested_at           timestamptz NOT NULL,
    decision               text,
    decided_by             uuid        REFERENCES app_user (id),
    decided_at             timestamptz,
    lapses_on              date,
    CONSTRAINT exemption_basis_check CHECK (basis IN ('PRIOR_VA', 'SCHEDULED_VA')),
    CONSTRAINT exemption_evidence_check CHECK (length(evidence) BETWEEN 1 AND 4000),
    CONSTRAINT exemption_previous_state_check CHECK (previous_state IN ('NOT_STARTED', 'DRAFT', 'IN_REVIEW',
        'RETURNED_FOR_REWORK', 'AWAITING_ENDORSEMENT')),
    CONSTRAINT exemption_decision_check CHECK (decision IN ('APPROVED', 'REJECTED')),
    CONSTRAINT exemption_decided_check CHECK ((decision IS NULL) = (decided_by IS NULL)
                                              AND (decision IS NULL) = (decided_at IS NULL)),
    CONSTRAINT exemption_lapse_check CHECK ((lapses_on IS NOT NULL)
                                            = coalesce(decision = 'APPROVED' AND basis = 'SCHEDULED_VA', false))
);

CREATE INDEX exemption_submission_idx ON exemption (submission_id, seq);
CREATE UNIQUE INDEX exemption_pending_key ON exemption (submission_id) WHERE decision IS NULL;

-- A VA Submission row can now be Not Started: one a request created goes back to it when the request is rejected, and
-- a later start (or ticket 42's lapse) takes it from there. A System with no row is Not Started as before. Such a row
-- keeps when and by whom the request created it (started_at, started_by).
ALTER TABLE va_submission DROP CONSTRAINT va_submission_state_check;
ALTER TABLE va_submission ADD CONSTRAINT va_submission_state_check
    CHECK (state IN ('NOT_STARTED', 'DRAFT', 'IN_REVIEW', 'RETURNED_FOR_REWORK', 'AWAITING_ENDORSEMENT', 'ENDORSED',
                     'EXEMPTION_REQUESTED', 'EXEMPTED', 'WITHDRAWN', 'NOT_ENDORSED'));

-- The audit log learns the Exemption an entry is about and its basis: an id and a fixed code, never the evidence.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version', 'state', 'decision', 'endorsed_late', 'export_id',
                              'export_type', 'format', 'classification', 'cycle', 'quarter',
                              'exemption_id', 'basis'] = '{}'::jsonb);

-- The notifications (V136, V141) learn the request to both VA POCs, as a pair that clears once either decides, and the
-- decision to the SMO/Tech Lead.
ALTER TABLE notification DROP CONSTRAINT notification_action_check;
ALTER TABLE notification ADD CONSTRAINT notification_action_check CHECK (action IN ('REVIEW', 'REWORK', 'ENDORSE',
    'SUBMIT_OVERDUE', 'REVIEW_OVERDUE', 'ENDORSE_OVERDUE', 'SYSTEM_MOVED', 'SYSTEM_TRANSFERRED_TO_YOU',
    'SYSTEM_TRANSFERRED_FROM_YOU', 'NOT_ENDORSED', 'VA_POC_REWORK', 'VA_POC_RETURNED', 'DECIDE_EXEMPTION',
    'EXEMPTION_APPROVED', 'EXEMPTION_REJECTED'));
ALTER TABLE notification DROP CONSTRAINT notification_awaits_check;
ALTER TABLE notification ADD CONSTRAINT notification_awaits_check CHECK (awaits IN ('ENDORSEMENT', 'EXEMPTION'));
