-- Retention and disposal (ticket 49, story 87, ADR 0048). Records are kept for the VA Cycle plus 3 years; a superseded
-- Scan Report file's content once its VA Submission is Endorsed; an export's row once its 24 hours are up. The retention
-- job lists what is due on a disposal list, a VA POC approves the list with a fresh sign-in, and the executor hard-deletes
-- exactly what it names and writes one tombstone audit entry. Nothing else removes a record: every other removal before
-- retention ends is a soft delete (a flag or a status), and the append-only tables still refuse it.
--
-- disposal_list: one list the retention job wrote.
--   status        DRAFT (written by the job, waiting for a VA POC), SUPERSEDED (the job found something different before
--                 anyone approved it, and wrote a new DRAFT), APPROVED (a VA POC approved it; the executor has not run
--                 yet), EXECUTED (disposed of). At most one list is DRAFT or APPROVED at a time
--   computed_on   the business date (Asia/Singapore) the job computed it on
--   cutoff        the instant before which the records of the Cycles it disposes of were written: the start of the
--                 VA Cycle after the newest of them. Audit entries, Register previews and notifications about no VA
--                 Submission written before it are disposed of; null when the list disposes of no Cycle
--   approved_by, approved_at, executed_at
--                 who approved it and when, and when it was executed (UTC)
-- disposal_item: one record, or one group of records, the list disposes of, with why. Ids and codes only: the targets
-- are deleted, so there is no foreign key to them, and the item stays as the record of what was disposed of.
--   kind          SUBMISSION (a VA Submission and everything that hangs off it), FILE (a Scan Report file's content and
--                 row, with its VA Submission), SUPERSEDED_FILE (a superseded file's content only: its row stays until
--                 its Cycle is disposed of), EXPORT (an export's row), SYSTEM (a retired System's row), CYCLE (the
--                 records of a Cycle that are about no one VA Submission) or AUDIT_RANGE (the audit entries before
--                 the cutoff)
--   target_id     the VA Submission, file, export or System; null for CYCLE and AUDIT_RANGE
--   system_id     the System the item is about, when there is one, so the list can name it
--   cycle_year    the VA Cycle whose retention ended; null for an export
--   reason        CYCLE_RETENTION_ENDED, SUPERSEDED_AFTER_ENDORSEMENT, EXPORT_EXPIRED or SYSTEM_RETIRED
--   entries       for AUDIT_RANGE: how many entries were before the cutoff when the list was computed
CREATE TABLE disposal_list (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq          bigint      GENERATED ALWAYS AS IDENTITY,
    status       text        NOT NULL,
    computed_on  date        NOT NULL,
    created_at   timestamptz NOT NULL,
    cutoff       timestamptz,
    approved_by  uuid        REFERENCES app_user (id),
    approved_at  timestamptz,
    executed_at  timestamptz,
    CONSTRAINT disposal_list_status_check CHECK (status IN ('DRAFT', 'SUPERSEDED', 'APPROVED', 'EXECUTED')),
    CONSTRAINT disposal_list_approved_check
        CHECK ((status IN ('APPROVED', 'EXECUTED')) = (approved_by IS NOT NULL AND approved_at IS NOT NULL)),
    CONSTRAINT disposal_list_executed_check CHECK ((status = 'EXECUTED') = (executed_at IS NOT NULL))
);

CREATE UNIQUE INDEX disposal_list_one_open_idx ON disposal_list ((true)) WHERE status IN ('DRAFT', 'APPROVED');

CREATE TABLE disposal_item (
    list_id    uuid     NOT NULL REFERENCES disposal_list (id),
    seq        bigint   GENERATED ALWAYS AS IDENTITY,
    kind       text     NOT NULL,
    target_id  uuid,
    system_id  uuid,
    cycle_year smallint,
    reason     text     NOT NULL,
    entries    bigint,
    PRIMARY KEY (list_id, seq),
    CONSTRAINT disposal_item_kind_check
        CHECK (kind IN ('SUBMISSION', 'FILE', 'SUPERSEDED_FILE', 'EXPORT', 'SYSTEM', 'CYCLE', 'AUDIT_RANGE')),
    CONSTRAINT disposal_item_reason_check
        CHECK (reason IN ('CYCLE_RETENTION_ENDED', 'SUPERSEDED_AFTER_ENDORSEMENT', 'EXPORT_EXPIRED', 'SYSTEM_RETIRED')),
    CONSTRAINT disposal_item_target_check
        CHECK ((kind IN ('CYCLE', 'AUDIT_RANGE')) = (target_id IS NULL)),
    CONSTRAINT disposal_item_entries_check CHECK ((kind = 'AUDIT_RANGE') = (entries IS NOT NULL))
);

-- report_file.content_disposed_at: when (UTC) a superseded file's content was deleted from the file store by an approved
-- disposal, ahead of its Cycle (reviewer decisions, 2026-10-18). The row, its name, hash and links stay until the Cycle is
-- disposed of; a download answers "disposed" from then on. Only a superseded file is disposed of early.
ALTER TABLE report_file
    ADD COLUMN content_disposed_at timestamptz,
    ADD CONSTRAINT report_file_content_disposed_check
        CHECK (content_disposed_at IS NULL OR superseded_by IS NOT NULL);

-- The one way through the append-only tables. A delete is allowed only inside the executor's transaction, which names
-- the list it executes in the transaction-local setting annualva.disposal_list, and only while that list is APPROVED: the
-- executor marks it EXECUTED in the same transaction, after the deletes. A setting alone opens nothing (a list must have
-- been approved, by a VA POC with a fresh sign-in), an UPDATE or a TRUNCATE is refused always, and the Declaration
-- wordings are never disposed of. Ordinary writes see the triggers exactly as before.
CREATE FUNCTION disposal_running() RETURNS uuid
    LANGUAGE sql STABLE AS $$
    SELECT id FROM disposal_list
    WHERE status = 'APPROVED' AND id::text = nullif(current_setting('annualva.disposal_list', true), '')
$$;

CREATE OR REPLACE FUNCTION audit_entry_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND disposal_running() IS NOT NULL THEN
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'audit_entry is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE OR REPLACE FUNCTION finding_status_proposal_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND disposal_running() IS NOT NULL THEN
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'finding_status_proposal is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE OR REPLACE FUNCTION bulk_batch_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND disposal_running() IS NOT NULL THEN
        RETURN NULL;
    END IF;
    RAISE EXCEPTION 'bulk_batch is append-only: % is not allowed', TG_OP;
END;
$$;

-- Shared by declaration_text, declaration, finding_review, report_review, batch_review, submission_return and
-- submission_return_item; the wordings (declaration_text) are kept whatever is disposed of.
CREATE OR REPLACE FUNCTION declaration_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' AND TG_TABLE_NAME <> 'declaration_text' AND disposal_running() IS NOT NULL THEN
        RETURN NULL;
    END IF;
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$;

-- An audit entry is deleted only within the approved range: before the list's cutoff. Per row, after the statement-level
-- check above, so even the executor cannot remove an entry written after the cutoff, the tombstone included.
CREATE FUNCTION audit_entry_disposal_range() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM disposal_list
                   WHERE id = disposal_running() AND cutoff IS NOT NULL AND OLD.occurred_at < cutoff) THEN
        RAISE EXCEPTION 'audit_entry is append-only: an entry outside the approved disposal range is kept';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER audit_entry_disposal_range
    BEFORE DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION audit_entry_disposal_range();

-- The audit log learns the disposal list an entry is about and the counts its tombstone records: ids and numbers only.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version', 'state', 'decision', 'endorsed_late', 'export_id',
                              'export_type', 'format', 'classification', 'cycle', 'quarter', 'disposal_list_id',
                              'submissions', 'files', 'superseded_files', 'exports', 'systems',
                              'audit_entries'] = '{}'::jsonb);
