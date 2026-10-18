-- How a Scan Report is provided, and Findings entered by hand (ticket 22, story 23, ADR 0030).
--
-- report_slot: one Scan Report of a VA Submission. A VA Submission with no row for a Scan Report uploads it (UPLOAD); a
-- row is written the first time the Scan Report's mode is set or a report file or a Finding by hand is added to it, and it
-- is the row those writes lock, so a mode change and an upload or an entry by hand wait for each other.
--   mode           UPLOAD (the tool's machine-readable report is uploaded), NOT_APPLICABLE (the Scan Report does not apply
--                  to the System: Dependency-Check or Burp Suite only, Secure Configuration is always required) or MANUAL
--                  (the tool cannot export a machine-readable report, so its Findings are typed in)
--   justification  why, for NOT_APPLICABLE and MANUAL, and only for them: what the person typed, cleaned and 1 to 4000
--                  characters (IM8 as-1). Data: never logged or audited
--   review_state   for NOT_APPLICABLE, and only for it: the Cyber Manager reviews the justification like a Finding
--                  (PENDING, CONFIRMED or DISPUTED); a new justification puts it back to PENDING. The review engine
--                  (ticket 28) confirms and Disputes it
CREATE TABLE report_slot (
    submission_id uuid NOT NULL REFERENCES va_submission (id),
    report_type   text NOT NULL,
    mode          text NOT NULL,
    justification text,
    review_state  text,
    PRIMARY KEY (submission_id, report_type),
    CONSTRAINT report_slot_type_check
        CHECK (report_type IN ('DEPENDENCY_CHECK', 'SECURE_CONFIGURATION', 'BURP_SUITE')),
    CONSTRAINT report_slot_mode_check CHECK (mode IN ('UPLOAD', 'NOT_APPLICABLE', 'MANUAL')),
    CONSTRAINT report_slot_secure_configuration_check
        CHECK (report_type <> 'SECURE_CONFIGURATION' OR mode <> 'NOT_APPLICABLE'),
    CONSTRAINT report_slot_justification_check
        CHECK ((mode = 'UPLOAD') = (justification IS NULL)
               AND (justification IS NULL OR char_length(justification) BETWEEN 1 AND 4000)),
    CONSTRAINT report_slot_review_check
        CHECK ((mode = 'NOT_APPLICABLE') = (review_state IS NOT NULL)
               AND (review_state IS NULL OR review_state IN ('PENDING', 'CONFIRMED', 'DISPUTED')))
);

-- finding.source: PARSED (read from a report file by a parser, every Finding before this ticket) or MANUAL (typed in by the
-- SMO/Tech Lead for a Scan Report in MANUAL mode, always shown as Manually Entered). Filled PARSED for every existing row
-- by a default that is then dropped, so every write names it.
-- finding.title: what the Finding is, in words; a Finding entered by hand has one (1 to 200 characters), and a parsed one
-- has none yet. A Finding entered by hand also keeps its identifier within 200 characters and its package or rule within
-- 500 (IM8 as-1); a parsed one is bounded by its parser.
ALTER TABLE finding
    ADD COLUMN source text NOT NULL DEFAULT 'PARSED',
    ADD COLUMN title  text,
    ADD CONSTRAINT finding_source_check CHECK (source IN ('PARSED', 'MANUAL')),
    ADD CONSTRAINT finding_title_check CHECK (title IS NULL OR char_length(title) BETWEEN 1 AND 200),
    ADD CONSTRAINT finding_manual_check
        CHECK (source <> 'MANUAL'
               OR (title IS NOT NULL AND char_length(identifier) <= 200 AND char_length(package_or_rule) <= 500));
ALTER TABLE finding ALTER COLUMN source DROP DEFAULT;

-- The audit log learns the Scan Report and the mode an entry is about: fixed codes.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode'] = '{}'::jsonb);
