-- The System Register (stories 2 and 12 to 14): the standing list of Systems, and the previews of a change to it.
-- This slice reads the Systems to classify an upload and stores the preview; the Register apply (ticket 10) writes
-- Systems and adds the foreign key from role_grant.system_id.
--
-- va_system: one row per System, matched by its System ID (the template's first column) and never deleted.
--   id          the System's own identifier everywhere else in the app (role_grant, audit_entry, VA Submissions)
--   code        the System ID the VA POC types in the template: the business identifier, unique without regard to case
--   quarter     1 to 4: the Quarter of the VA Cycle in which the System does its VA (the template's Q1 to Q4)
--   added_on    the business date (Asia/Singapore) the System was added; a System added partway through a Quarter
--               gets its own windows from it
--   retired_at  set when a re-upload no longer lists the System; the row and its history stay
-- Whether an SMO is an Unlinked SMO is not stored: it is read from app_user when asked, so it clears the moment the
-- person signs in. The CHECK on the two emails is the Register's own rule that a System's Cyber Manager is never its
-- SMO/Tech Lead (story 14), kept in the database as well as in the preview.
CREATE TABLE va_system (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    code       citext      NOT NULL,
    name       text        NOT NULL,
    smo_email  citext      NOT NULL,
    cm_email   citext      NOT NULL,
    quarter    smallint    NOT NULL,
    added_on   date        NOT NULL,
    retired_at timestamptz,
    CONSTRAINT va_system_code_key     UNIQUE (code),
    CONSTRAINT va_system_code_check   CHECK (length(code) BETWEEN 1 AND 64),
    CONSTRAINT va_system_name_check   CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT va_system_quarter_check CHECK (quarter BETWEEN 1 AND 4),
    CONSTRAINT va_system_cm_not_smo_check CHECK (smo_email <> cm_email)
);

-- register_upload: one uploaded Register file's preview. Nothing in the Register changes until a preview is applied.
--   status      PREVIEW (waiting), APPLIED or DISCARDED (the last two are written by the apply ticket)
--   created_by  the VA POC who uploaded the file
--   expires_at  one hour after created_at: an expired preview can no longer be read or applied
--   rows        the preview itself, one JSON list in the order rows are shown: the file's rows in sheet order, then
--               the Systems the file leaves out. Each element is a PreviewRow of the register module: sheetRow,
--               systemId (as typed), existingSystemId, outcome (ADD, UPDATE, RETIRE, NO_CHANGE or BLOCKED), reasons
--               (fixed codes, only for BLOCKED), unlinkedSmo, and the System before and after. It holds sanitised
--               text only, never a control character or lone surrogate. A preview is made once, read whole and
--               applied whole, so it is a document and not a table of rows (ADR 0017).
-- The file itself is not kept: what it held that matters is in rows.
CREATE TABLE register_upload (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    status     text        NOT NULL DEFAULT 'PREVIEW',
    created_by uuid        NOT NULL REFERENCES app_user (id),
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    rows       jsonb       NOT NULL,
    CONSTRAINT register_upload_status_check CHECK (status IN ('PREVIEW', 'APPLIED', 'DISCARDED')),
    CONSTRAINT register_upload_expiry_check CHECK (expires_at > created_at),
    CONSTRAINT register_upload_rows_check CHECK (jsonb_typeof(rows) = 'array')
);

-- The audit log learns one more detail key: the preview an entry is about (an id, never file content).
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id'] = '{}'::jsonb);
