-- The scan date and target confirmed by the SMO/Tech Lead, and the age rules (ticket 23, stories 25 and 26, ADR 0031).
--
-- report_file gains what the SMO/Tech Lead confirmed or corrected of what the parser pre-filled (scan_date_from_file and
-- target_from_file, V10), kept beside it so that a reader sees both:
--   scan_date_confirmed  the business date (Asia/Singapore) the scan was made, as the person gave it: not after the day it
--                        was given (checked by the application against its clock). The age rule judges this date, else the
--                        business date of scan_date_from_file (never a Burp export time, which is not a scan date). It
--                        is "edited", and flagged, when it differs from the business date of scan_date_from_file
--   target_confirmed     the project, host or application the person says was scanned, cleaned to one line, 1 to 200
--                        characters (IM8 as-1). It is "edited", and flagged, when it differs from target_from_file. Report
--                        content: never logged or audited
-- Both are given together and only for a parsed file.
ALTER TABLE report_file
    ADD COLUMN scan_date_confirmed date,
    ADD COLUMN target_confirmed    text,
    ADD CONSTRAINT report_file_confirmed_check
        CHECK ((scan_date_confirmed IS NULL) = (target_confirmed IS NULL)
               AND (pipeline_status = 'PARSED' OR scan_date_confirmed IS NULL)),
    ADD CONSTRAINT report_file_target_confirmed_check
        CHECK (target_confirmed IS NULL OR char_length(target_confirmed) BETWEEN 1 AND 200);

-- A file whose scan date is after today's business date is rejected by the pipeline's last stage, the age check.
ALTER TABLE report_file DROP CONSTRAINT report_file_status_check;
ALTER TABLE report_file ADD CONSTRAINT report_file_status_check
    CHECK (pipeline_status IN ('QUARANTINED', 'SCAN_SUBMITTED', 'SCAN_CLEAN', 'TYPE_OK', 'SCHEMA_OK', 'PARSED', 'READY',
                               'REJECTED_MALWARE', 'REJECTED_SANITIZED', 'REJECTED_SCAN_TIMEOUT', 'REJECTED_TYPE',
                               'REJECTED_TOO_LARGE', 'REJECTED_TOO_MANY_FINDINGS', 'REJECTED_UNREADABLE',
                               'REJECTED_SCHEMA', 'REJECTED_HASH_MISMATCH', 'REJECTED_ENCRYPTED',
                               'REJECTED_FUTURE_DATED'));

-- The audit log learns whether a confirmation changed the scan date or the target from what the file says: yes/no flags,
-- never the values (reviewer decisions, "A changed scan date is flagged, not blocked").
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed'] = '{}'::jsonb);
