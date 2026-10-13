-- Findings read from Scan Reports (stories 20a, 21, 25 and 28). The Dependency-Check parser is the first to fill them; the
-- Secure Configuration and Burp parsers (tickets 15 to 17) add their rows to the same tables, and the Finding Status,
-- review and carry-over columns arrive with the tickets that own them.
--
-- finding: one vulnerability or configuration deviation of one VA Submission, identified by its identity key.
--   seq              the order Findings were first read in; never shown, only a stable tie-break for a list
--   system_id        the System of the VA Submission, so a Finding can be matched across VA Cycles (the key is scoped to the System)
--   report_type      the Scan Report it was read from
--   identity_key     the Finding's identity within a System, built by the parser: dc|<CVE or GHSA>|<package purl or file name>.
--                    It is part of the report's content and is never logged or audited
--   identity_hash    the lower-case hex SHA-256 of identity_key. A VA Submission holds one Finding per hash, whichever file or
--                    how many files it was read from; the hash, not the text, is indexed because a key can be long
--   identifier       the CVE, GHSA or rule id as the report names it
--   package_or_rule  the package (a purl, else the dependency's file name) or the rule the identifier applies to
--   severity         the highest severity of its occurrences: CRITICAL, HIGH, MEDIUM, LOW or INFORMATIONAL
-- finding_occurrence: one place a Finding was reported, in one file of the Scan Report.
--   report_file_id   the file it was read from. One Finding read from two files has an occurrence in each
--   locator          the component or source file as the report names it; one occurrence per distinct locator and file
--   severity         the severity at this place
--
-- report_file gains what the parser learnt from the file, for the SMO/Tech Lead to confirm (a later ticket stores the
-- confirmation): scan_date_from_file (the instant the report says the scan was made) and target_from_file (the project the
-- report says it scanned). A file has them only once it has been parsed, and PARSED is the status of a file the parser read.
ALTER TABLE report_file
    ADD COLUMN scan_date_from_file timestamptz,
    ADD COLUMN target_from_file    text;

ALTER TABLE report_file DROP CONSTRAINT report_file_status_check;
ALTER TABLE report_file ADD CONSTRAINT report_file_status_check
    CHECK (pipeline_status IN ('QUARANTINED', 'SCAN_SUBMITTED', 'SCAN_CLEAN', 'TYPE_OK', 'SCHEMA_OK', 'PARSED',
                               'REJECTED_MALWARE', 'REJECTED_SANITIZED', 'REJECTED_SCAN_TIMEOUT', 'REJECTED_TYPE',
                               'REJECTED_TOO_LARGE', 'REJECTED_UNREADABLE', 'REJECTED_SCHEMA',
                               'REJECTED_HASH_MISMATCH'));

ALTER TABLE report_file DROP CONSTRAINT report_file_clean_copy_check;
ALTER TABLE report_file ADD CONSTRAINT report_file_clean_copy_check
    CHECK (pipeline_status NOT IN ('SCAN_CLEAN', 'TYPE_OK', 'SCHEMA_OK', 'PARSED') OR clean_key IS NOT NULL);

ALTER TABLE report_file ADD CONSTRAINT report_file_prefill_check
    CHECK (pipeline_status = 'PARSED' OR (scan_date_from_file IS NULL AND target_from_file IS NULL));
ALTER TABLE report_file ADD CONSTRAINT report_file_target_check
    CHECK (target_from_file IS NULL OR length(target_from_file) BETWEEN 1 AND 200);

CREATE TABLE finding (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq             bigint      GENERATED ALWAYS AS IDENTITY,
    submission_id   uuid        NOT NULL REFERENCES va_submission (id),
    system_id       uuid        NOT NULL REFERENCES va_system (id),
    report_type     text        NOT NULL,
    identity_key    text        NOT NULL,
    identity_hash   text        NOT NULL,
    identifier      text        NOT NULL,
    package_or_rule text        NOT NULL,
    severity        text        NOT NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT finding_submission_identity_key UNIQUE (submission_id, identity_hash),
    CONSTRAINT finding_type_check
        CHECK (report_type IN ('DEPENDENCY_CHECK', 'SECURE_CONFIGURATION', 'BURP_SUITE')),
    CONSTRAINT finding_hash_check CHECK (identity_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT finding_severity_check CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFORMATIONAL')),
    CONSTRAINT finding_text_check
        CHECK (length(identity_key) > 0 AND length(identifier) > 0 AND length(package_or_rule) > 0)
);

CREATE TABLE finding_occurrence (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    finding_id     uuid        NOT NULL REFERENCES finding (id),
    report_file_id uuid        NOT NULL REFERENCES report_file (id),
    locator        text        NOT NULL,
    severity       text        NOT NULL,
    CONSTRAINT finding_occurrence_place_key UNIQUE (finding_id, report_file_id, locator),
    CONSTRAINT finding_occurrence_severity_check
        CHECK (severity IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFORMATIONAL')),
    CONSTRAINT finding_occurrence_locator_check CHECK (length(locator) > 0)
);

CREATE INDEX finding_occurrence_file_idx ON finding_occurrence (report_file_id);
