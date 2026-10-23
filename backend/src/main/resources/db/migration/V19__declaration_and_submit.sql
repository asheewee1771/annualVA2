-- The Declaration and submitting a VA Submission for review (ticket 25, stories 40 and 41, ADR 0032).
--
-- declaration_text: the fixed wording the SMO/Tech Lead accepts when submitting, one row per version. The wording is
-- owned by people outside the app (ticket 24): a new wording is a new version, added by a migration, and a version is
-- never changed or removed. The current version is the highest.
--   version      1, 2, ...
--   text         the wording, 1 to 4000 characters
--   placeholder  true while the wording is not the agreed one: the screen marks it as a placeholder
-- declaration: what one SMO/Tech Lead accepted when submitting one VA Submission, kept as it was shown (a snapshot of the
-- text, not a reference only), with who accepted it and when. A VA Submission returned for rework and submitted again has
-- one row per submit; none is ever changed or removed.
CREATE TABLE declaration_text (
    version     integer PRIMARY KEY,
    text        text    NOT NULL,
    placeholder boolean NOT NULL,
    CONSTRAINT declaration_text_version_check CHECK (version > 0),
    CONSTRAINT declaration_text_text_check CHECK (char_length(text) BETWEEN 1 AND 4000)
);

CREATE TABLE declaration (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq           bigint      GENERATED ALWAYS AS IDENTITY,
    submission_id uuid        NOT NULL REFERENCES va_submission (id),
    version       integer     NOT NULL REFERENCES declaration_text (version),
    text          text        NOT NULL,
    accepted_by   uuid        NOT NULL REFERENCES app_user (id),
    accepted_at   timestamptz NOT NULL,
    CONSTRAINT declaration_text_length_check CHECK (char_length(text) BETWEEN 1 AND 4000)
);

CREATE INDEX declaration_submission_idx ON declaration (submission_id, seq);

-- Both are history: never changed or removed. Per statement, as for the proposals (V14) and the audit log (V5).
-- Retention (ticket 49) replaces the trigger on declaration by a later migration, deliberately.
CREATE FUNCTION declaration_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP;
END;
$$;

CREATE TRIGGER declaration_text_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON declaration_text
    FOR EACH STATEMENT EXECUTE FUNCTION declaration_append_only();

CREATE TRIGGER declaration_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON declaration
    FOR EACH STATEMENT EXECUTE FUNCTION declaration_append_only();

-- PLACEHOLDER SEED. The wording below is not the agreed Declaration. Ticket 24 (ready-for-human) agrees the wording and
-- its owner with the PO and the cyber team; the agreed text is then added as version 2 by a new migration (placeholder
-- false), before UAT. No code changes: the current version is the highest.
INSERT INTO declaration_text (version, text, placeholder) VALUES (1,
    'PLACEHOLDER WORDING, NOT YET AGREED. I declare that the Scan Reports in this VA Submission were produced from scans '
    || 'of this System, that every Finding has been given a proposed Finding Status in good faith, and that what I have '
    || 'entered is accurate and complete to the best of my knowledge.',
    true);

-- va_submission learns when, and by whom, it was last submitted for review. Each submit (the first, and each after a
-- return for rework) writes them; the declaration rows keep every one.
ALTER TABLE va_submission
    ADD COLUMN submitted_at timestamptz,
    ADD COLUMN submitted_by uuid REFERENCES app_user (id),
    ADD CONSTRAINT va_submission_submitted_check CHECK ((submitted_at IS NULL) = (submitted_by IS NULL));

-- The audit log learns which Declaration version a submit accepted: a number, never the wording.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version'] = '{}'::jsonb);
