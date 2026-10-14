-- Checks that did not complete (ticket 15, stories 20b and 29): a SCAP result of error or unknown is neither a pass nor a
-- fail, and is shown as a Finding of its own kind (CONTEXT.md, "Check that did not complete").
--
-- finding_occurrence.did_not_complete
--   true when the check ran at this place (a host) and neither passed nor failed. It is kept on the occurrence and not on the
--   Finding, because one rule can fail on one host and give an error on another: the Finding is "a check that did not
--   complete" only when it did not complete at every place it was reported, which the list works out when it reads (a
--   second stored copy could disagree with the places). A failed check, and every Finding of the other Scan Reports, is false.
ALTER TABLE finding_occurrence
    ADD COLUMN did_not_complete boolean NOT NULL DEFAULT false;

-- A report with more Findings or places than one file may hold is rejected as TOO_MANY_FINDINGS (not as TOO_LARGE, which is a
-- file over the size limit for its type, nor as SCHEMA, which is a file of the wrong shape). The status holds the code in the
-- one place report_file_reject_check already ties them.
ALTER TABLE report_file DROP CONSTRAINT report_file_status_check;
ALTER TABLE report_file ADD CONSTRAINT report_file_status_check
    CHECK (pipeline_status IN ('QUARANTINED', 'SCAN_SUBMITTED', 'SCAN_CLEAN', 'TYPE_OK', 'SCHEMA_OK', 'PARSED',
                               'REJECTED_MALWARE', 'REJECTED_SANITIZED', 'REJECTED_SCAN_TIMEOUT', 'REJECTED_TYPE',
                               'REJECTED_TOO_LARGE', 'REJECTED_TOO_MANY_FINDINGS', 'REJECTED_UNREADABLE',
                               'REJECTED_SCHEMA', 'REJECTED_HASH_MISMATCH'));
