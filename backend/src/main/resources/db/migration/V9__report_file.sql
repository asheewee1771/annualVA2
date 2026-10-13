-- The Scan Report upload pipeline (story 20, 24, 27, 78): every file an SMO/Tech Lead uploads is quarantined,
-- malware-scanned, type-checked and schema-checked before anything reads it. This slice stores the file and walks it
-- through those stages; the parsers (tickets 14 to 17) and the evidence path (ticket 18) extend it.
--
-- report_file: one uploaded file of one Scan Report of a VA Submission.
--   seq              the order files were received in, which breaks a tie between files with the same uploaded_at: "oldest
--                    first" is deterministic. Never shown; id is the public identifier
--   report_type      DEPENDENCY_CHECK, SECURE_CONFIGURATION or BURP_SUITE: the Scan Report the person declared
--   kind             MACHINE_READABLE (read for Findings) or EVIDENCE (a PDF or HTML attached to be read by people)
--   display_name     the name the person gave it, stripped of any path and control character; shown and used as the
--                    download name, never logged
--   size_bytes, upload_sha256
--                    what was received; the hash is of the bytes as uploaded, lower-case hex
--   clean_sha256     the hash of the scanner's unchanged copy, set when the file reached the checks: it equals upload_sha256,
--                    because a copy that differs, or a file the scanner had to change, is rejected
--   detected_format  what the type stage found in the bytes (never the declared type); null until it has run
--   quarantine_key   where the unscanned bytes are held. It stays until the bytes are really deleted: the scan job deletes
--                    them after the transaction that records the outcome has committed (a clean or rejected file), and only
--                    then clears the key, so a failed delete is retried and a blob, malware included, is never left with
--                    no record of it
--   clean_key        where the scanner's unchanged copy is held (the file's own id); set only for a file that passed every
--                    stage. A rejected file keeps no clean copy
--   pipeline_status  QUARANTINED, SCAN_SUBMITTED, SCAN_CLEAN, TYPE_OK, SCHEMA_OK, or REJECTED_<reject_code>. The stages
--                    run in the fixed order quarantine, malware scan, type allowlist, schema check; later tickets append
--                    parse, pre-fill and age. Only a file that reached SCHEMA_OK or later can be viewed or downloaded
--   reject_code      why it was rejected: MALWARE, SANITIZED (the scanner had to change the file, which is not accepted),
--                    SCAN_TIMEOUT, TYPE, TOO_LARGE, UNREADABLE, SCHEMA or HASH_MISMATCH
--   scan_ticket      the scanner's handle for the current attempt; opaque and never shown or logged
--   scan_attempts    scan attempts made (the first and the two retries the spec allows); at most 3
--   scan_submitted_at when the current attempt began (UTC), or failed to begin: the next attempt waits the attempt timeout
--                    from it, so a scanner that is down is not tried three times in a minute. By the application clock
-- report_file_stage: the order the stages ran in, one row each time a file enters a status. The status of a file shows it,
-- and a test proves the fixed order by it.
CREATE TABLE report_file (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq               bigint      GENERATED ALWAYS AS IDENTITY,
    submission_id     uuid        NOT NULL REFERENCES va_submission (id),
    report_type       text        NOT NULL,
    kind              text        NOT NULL,
    display_name      text        NOT NULL,
    size_bytes        bigint      NOT NULL,
    upload_sha256     text        NOT NULL,
    clean_sha256      text,
    detected_format   text,
    quarantine_key    text,
    clean_key         text,
    pipeline_status   text        NOT NULL,
    reject_code       text,
    scan_ticket       text,
    scan_attempts     smallint    NOT NULL DEFAULT 0,
    scan_submitted_at timestamptz,
    uploaded_by       uuid        NOT NULL REFERENCES app_user (id),
    uploaded_at       timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    CONSTRAINT report_file_type_check
        CHECK (report_type IN ('DEPENDENCY_CHECK', 'SECURE_CONFIGURATION', 'BURP_SUITE')),
    CONSTRAINT report_file_kind_check CHECK (kind IN ('MACHINE_READABLE', 'EVIDENCE')),
    CONSTRAINT report_file_name_check CHECK (length(display_name) BETWEEN 1 AND 200),
    CONSTRAINT report_file_size_check CHECK (size_bytes > 0),
    CONSTRAINT report_file_upload_hash_check CHECK (upload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT report_file_clean_hash_check CHECK (clean_sha256 IS NULL OR clean_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT report_file_status_check
        CHECK (pipeline_status IN ('QUARANTINED', 'SCAN_SUBMITTED', 'SCAN_CLEAN', 'TYPE_OK', 'SCHEMA_OK',
                                   'REJECTED_MALWARE', 'REJECTED_SANITIZED', 'REJECTED_SCAN_TIMEOUT', 'REJECTED_TYPE', 'REJECTED_TOO_LARGE',
                                   'REJECTED_UNREADABLE', 'REJECTED_SCHEMA', 'REJECTED_HASH_MISMATCH')),
    -- A rejected file says why, in one place only: the status is REJECTED_ plus the code, and no other file has a code.
    CONSTRAINT report_file_reject_check
        CHECK ((reject_code IS NULL AND pipeline_status NOT LIKE 'REJECTED%')
               OR pipeline_status = 'REJECTED_' || reject_code),
    CONSTRAINT report_file_attempts_check CHECK (scan_attempts BETWEEN 0 AND 3),
    -- A file past the checks has its clean copy; a file still to be scanned has its quarantined bytes; a rejected file
    -- keeps no clean copy.
    CONSTRAINT report_file_clean_copy_check
        CHECK (pipeline_status NOT IN ('SCAN_CLEAN', 'TYPE_OK', 'SCHEMA_OK') OR clean_key IS NOT NULL),
    CONSTRAINT report_file_quarantine_copy_check
        CHECK (pipeline_status NOT IN ('QUARANTINED', 'SCAN_SUBMITTED') OR quarantine_key IS NOT NULL),
    CONSTRAINT report_file_rejected_keeps_no_clean_copy_check CHECK (reject_code IS NULL OR clean_key IS NULL)
);

CREATE INDEX report_file_submission_idx ON report_file (submission_id, seq);
CREATE INDEX report_file_pipeline_idx ON report_file (pipeline_status, seq)
    WHERE pipeline_status IN ('QUARANTINED', 'SCAN_SUBMITTED');
CREATE INDEX report_file_leftover_idx ON report_file (seq) WHERE quarantine_key IS NOT NULL;

CREATE TABLE report_file_stage (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq        bigint      GENERATED ALWAYS AS IDENTITY,
    file_id    uuid        NOT NULL REFERENCES report_file (id),
    status     text        NOT NULL,
    entered_at timestamptz NOT NULL
);

CREATE INDEX report_file_stage_file_idx ON report_file_stage (file_id, seq);
