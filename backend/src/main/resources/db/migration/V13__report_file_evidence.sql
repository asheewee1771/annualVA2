-- Supporting evidence (ticket 18, ADR 0026): a PDF or HTML report attached to a Scan Report for people to read. It goes
-- through quarantine, the malware scan and the type check like every file, and is then never read: no schema check, no
-- parse. A finished evidence file rests in READY (a machine-readable one rests in PARSED), and detected_format holds PDF or
-- HTML for it.
ALTER TABLE report_file DROP CONSTRAINT report_file_status_check;
ALTER TABLE report_file ADD CONSTRAINT report_file_status_check
    CHECK (pipeline_status IN ('QUARANTINED', 'SCAN_SUBMITTED', 'SCAN_CLEAN', 'TYPE_OK', 'SCHEMA_OK', 'PARSED', 'READY',
                               'REJECTED_MALWARE', 'REJECTED_SANITIZED', 'REJECTED_SCAN_TIMEOUT', 'REJECTED_TYPE',
                               'REJECTED_TOO_LARGE', 'REJECTED_TOO_MANY_FINDINGS', 'REJECTED_UNREADABLE',
                               'REJECTED_SCHEMA', 'REJECTED_HASH_MISMATCH', 'REJECTED_ENCRYPTED'));

ALTER TABLE report_file DROP CONSTRAINT report_file_clean_copy_check;
ALTER TABLE report_file ADD CONSTRAINT report_file_clean_copy_check
    CHECK (pipeline_status NOT IN ('SCAN_CLEAN', 'TYPE_OK', 'SCHEMA_OK', 'PARSED', 'READY') OR clean_key IS NOT NULL);

-- Evidence is never schema-checked or parsed, and only evidence is READY: no row can say otherwise.
ALTER TABLE report_file ADD CONSTRAINT report_file_evidence_never_parsed_check
    CHECK (CASE kind
               WHEN 'EVIDENCE' THEN pipeline_status NOT IN ('SCHEMA_OK', 'PARSED')
               ELSE pipeline_status <> 'READY'
           END);
