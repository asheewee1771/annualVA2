-- The review engine, first part (ticket 28, stories 50 and 58): the Cyber Manager confirms or Disputes each proposed
-- Finding Status, and each not-applicable justification, of a VA Submission In Review.
--
-- finding_review: one review of one proposal of a Finding. A review is of the proposal it names, never of the Finding in
-- general, so a review of an earlier proposal can never be read as one of the current proposal: the Finding's review
-- state is CONFIRMED or DISPUTED only while its current proposal is the one reviewed, and a new proposal puts it back to
-- PENDING (V14).
--   proposal_id   the proposal reviewed; a proposal of the same Finding (the foreign key says so)
--   decision      CONFIRMED or DISPUTED
--   comment       what the Cyber Manager wrote: required for DISPUTED, optional for CONFIRMED, 1 to 4000 characters once
--                 cleaned (IM8 as-1). Data: never logged or audited
--   reviewed_by, reviewed_at
--                 who (an id) and when (UTC)
-- report_review: one review of a not-applicable Scan Report's justification. The justification itself is overwritten in
-- place when the SMO/Tech Lead rewrites it (ADR 0030 item 3), so the review keeps a copy of the wording it was about,
-- copied from the Scan Report by the statement that writes the review: after a Dispute and a rewrite, the disputed
-- wording is still beside its review.
--   justification the not-applicable justification as it was when it was reviewed, 1 to 4000 characters (decision
--                 2026-10-24)
--   justification_version
--                 the version of the justification reviewed (report_slot.justification_version)
-- Both are history: a review is never changed or removed; a later review is a new row and the latest one is current.
-- report_slot.justification_version: a counter that goes up each time a write could change what the Cyber Manager reviews
-- (the justification changes, or the mode changes, which puts a not-applicable review back to PENDING). A review of a
-- not-applicable justification names the version its screen showed and is refused when it is no longer the current one,
-- as a review of a Finding names its proposal. It starts at 0 for every Scan Report, existing ones included.
ALTER TABLE report_slot ADD COLUMN justification_version integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT report_slot_justification_version_check CHECK (justification_version >= 0);

ALTER TABLE finding_status_proposal ADD CONSTRAINT finding_status_proposal_finding_key UNIQUE (id, finding_id);

CREATE TABLE finding_review (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq         bigint      GENERATED ALWAYS AS IDENTITY,
    finding_id  uuid        NOT NULL REFERENCES finding (id),
    proposal_id uuid        NOT NULL,
    decision    text        NOT NULL,
    comment     text,
    reviewed_by uuid        NOT NULL REFERENCES app_user (id),
    reviewed_at timestamptz NOT NULL,
    CONSTRAINT finding_review_proposal_fkey FOREIGN KEY (proposal_id, finding_id)
        REFERENCES finding_status_proposal (id, finding_id),
    CONSTRAINT finding_review_decision_check CHECK (decision IN ('CONFIRMED', 'DISPUTED')),
    CONSTRAINT finding_review_comment_check
        CHECK ((comment IS NULL OR char_length(comment) BETWEEN 1 AND 4000)
               AND (decision <> 'DISPUTED' OR comment IS NOT NULL))
);

CREATE INDEX finding_review_finding_idx ON finding_review (finding_id, seq);

CREATE TABLE report_review (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq           bigint      GENERATED ALWAYS AS IDENTITY,
    submission_id uuid        NOT NULL,
    report_type   text        NOT NULL,
    decision      text        NOT NULL,
    comment       text,
    justification text        NOT NULL,
    justification_version integer NOT NULL,
    reviewed_by   uuid        NOT NULL REFERENCES app_user (id),
    reviewed_at   timestamptz NOT NULL,
    CONSTRAINT report_review_slot_fkey FOREIGN KEY (submission_id, report_type)
        REFERENCES report_slot (submission_id, report_type),
    CONSTRAINT report_review_decision_check CHECK (decision IN ('CONFIRMED', 'DISPUTED')),
    CONSTRAINT report_review_comment_check
        CHECK ((comment IS NULL OR char_length(comment) BETWEEN 1 AND 4000)
               AND (decision <> 'DISPUTED' OR comment IS NOT NULL)),
    CONSTRAINT report_review_justification_check CHECK (char_length(justification) BETWEEN 1 AND 4000)
);

CREATE INDEX report_review_slot_idx ON report_review (submission_id, report_type, seq);

-- Never changed or removed, per statement, as the proposals (V14) and the Declarations (V19). Retention (ticket 49)
-- replaces the triggers by a later migration, deliberately.
CREATE TRIGGER finding_review_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON finding_review
    FOR EACH STATEMENT EXECUTE FUNCTION declaration_append_only();

CREATE TRIGGER report_review_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON report_review
    FOR EACH STATEMENT EXECUTE FUNCTION declaration_append_only();

-- The audit log learns the decision of a review: CONFIRMED or DISPUTED, never the comment.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version', 'state', 'decision'] = '{}'::jsonb);
