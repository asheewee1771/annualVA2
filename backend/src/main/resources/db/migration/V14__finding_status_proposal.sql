-- Finding Status proposals (ticket 19, stories 28 and 35, ADR 0027): the SMO/Tech Lead proposes one of four statuses for
-- a Finding, with the field that status requires. Every proposal is a new row that is never changed or removed; the
-- Finding points at the latest one, and the Cyber Manager's review state (tickets 28 and later) is reset by a new one.
--
-- finding_status_proposal: one proposal.
--   status           REMEDIATED, FALSE_POSITIVE, RISK_ACCEPTED or REMEDIATION_PLANNED
--   evidence         REMEDIATED: what shows it was fixed
--   justification    FALSE_POSITIVE: why the Finding is not real
--   reason           RISK_ACCEPTED: why the risk is accepted
--   approver_name, approver_role
--                    RISK_ACCEPTED: who accepted it, as free text (the risk owner is usually outside the app)
--   approval_memo_file_id
--                    RISK_ACCEPTED, optional: a supporting evidence file of the same VA Submission (ticket 18's path)
--   plan, target_date
--                    REMEDIATION_PLANNED: the plan and the business date (Asia/Singapore) the fix is planned for
--   proposed_by, proposed_at
--                    who (an id) and when (UTC) it was proposed
-- A row carries exactly the fields of its status and no others, each text between 1 and 4000 characters (names and roles
-- 200), so no row can say otherwise whatever wrote it. The application cleans and bounds every text before it gets here
-- (IM8 as-1): the checks are the backstop.
CREATE TABLE finding_status_proposal (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq                   bigint      GENERATED ALWAYS AS IDENTITY,
    finding_id            uuid        NOT NULL REFERENCES finding (id),
    status                text        NOT NULL,
    evidence              text,
    justification         text,
    reason                text,
    approver_name         text,
    approver_role         text,
    approval_memo_file_id uuid        REFERENCES report_file (id),
    plan                  text,
    target_date           date,
    proposed_by           uuid        NOT NULL REFERENCES app_user (id),
    proposed_at           timestamptz NOT NULL,
    CONSTRAINT finding_status_proposal_status_check
        CHECK (status IN ('REMEDIATED', 'FALSE_POSITIVE', 'RISK_ACCEPTED', 'REMEDIATION_PLANNED')),
    CONSTRAINT finding_status_proposal_length_check
        CHECK ((evidence IS NULL OR char_length(evidence) BETWEEN 1 AND 4000)
               AND (justification IS NULL OR char_length(justification) BETWEEN 1 AND 4000)
               AND (reason IS NULL OR char_length(reason) BETWEEN 1 AND 4000)
               AND (plan IS NULL OR char_length(plan) BETWEEN 1 AND 4000)
               AND (approver_name IS NULL OR char_length(approver_name) BETWEEN 1 AND 200)
               AND (approver_role IS NULL OR char_length(approver_role) BETWEEN 1 AND 200)),
    CONSTRAINT finding_status_proposal_fields_check
        CHECK (CASE status
                   WHEN 'REMEDIATED' THEN evidence IS NOT NULL AND justification IS NULL AND reason IS NULL
                       AND approver_name IS NULL AND approver_role IS NULL AND approval_memo_file_id IS NULL
                       AND plan IS NULL AND target_date IS NULL
                   WHEN 'FALSE_POSITIVE' THEN justification IS NOT NULL AND evidence IS NULL AND reason IS NULL
                       AND approver_name IS NULL AND approver_role IS NULL AND approval_memo_file_id IS NULL
                       AND plan IS NULL AND target_date IS NULL
                   WHEN 'RISK_ACCEPTED' THEN reason IS NOT NULL AND approver_name IS NOT NULL
                       AND approver_role IS NOT NULL AND evidence IS NULL AND justification IS NULL
                       AND plan IS NULL AND target_date IS NULL
                   WHEN 'REMEDIATION_PLANNED' THEN plan IS NOT NULL AND target_date IS NOT NULL AND evidence IS NULL
                       AND justification IS NULL AND reason IS NULL AND approver_name IS NULL
                       AND approver_role IS NULL AND approval_memo_file_id IS NULL
                   ELSE false
               END)
);

CREATE INDEX finding_status_proposal_finding_idx ON finding_status_proposal (finding_id, seq);

-- A proposal is never changed or removed: its history is kept. Per statement, so a statement that matches no row is
-- refused too, as for the audit log (V5). Retention (ticket 49) replaces the trigger by a later migration, deliberately.
CREATE FUNCTION finding_status_proposal_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'finding_status_proposal is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER finding_status_proposal_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON finding_status_proposal
    FOR EACH STATEMENT EXECUTE FUNCTION finding_status_proposal_append_only();

-- finding gains the latest proposal and the review state of it. A Finding with no proposal has neither; a proposal always
-- starts PENDING (nobody has confirmed or Disputed it), and any new one puts the state back to PENDING, so a Cyber
-- Manager only ever re-reviews what changed (story 56).
ALTER TABLE finding
    ADD COLUMN current_proposal_id uuid REFERENCES finding_status_proposal (id),
    ADD COLUMN review_state        text,
    ADD CONSTRAINT finding_review_state_check CHECK (review_state IN ('PENDING', 'CONFIRMED', 'DISPUTED')),
    ADD CONSTRAINT finding_proposal_review_check CHECK ((current_proposal_id IS NULL) = (review_state IS NULL));

-- The audit log learns the status a proposal was for: one of four fixed codes, never any text of the proposal.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status'] = '{}'::jsonb);
