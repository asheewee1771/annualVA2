-- Deleting a report file or a Manually Entered Finding while the VA Submission is editable (ticket 60, stories 23 and
-- 39, ADR 0061). The SMO/Tech Lead undoes a mistake: a report file uploaded by mistake, or a Finding entered by hand by
-- mistake. Once a Scan Report holds nothing, its mode can change again (ADR 0030).
--
-- report_file.deleted_at, deleted_by
--   when (UTC) and by whom a report file was deleted. A deleted file is a soft delete (spec, "every other removal before
--   retention ends is a soft-delete"; ADR 0050 item 8): its row, its name and hashes and its content stay until its VA
--   Cycle is disposed of, like every other file of the VA Submission, but nothing lists, reads, counts, scans or
--   downloads it any more. Only a report file is deleted (supporting evidence is not); one a rescan has superseded only
--   once that rescan is deleted (the user's decision of 2026-11-22), and it stays superseded. Its places are removed in
--   the same transaction (finding_occurrence is not a history table).
ALTER TABLE report_file
    ADD COLUMN deleted_at timestamptz,
    ADD COLUMN deleted_by uuid REFERENCES app_user (id),
    ADD CONSTRAINT report_file_deleted_check
        CHECK ((deleted_at IS NULL) = (deleted_by IS NULL)
               AND (deleted_at IS NULL OR kind = 'MACHINE_READABLE'));

-- A file deleted while it was still being checked keeps its status, and the scan job deletes its unscanned bytes and then
-- forgets the key, as for a file that settled: so a deleted file still in quarantine may have no key left.
ALTER TABLE report_file DROP CONSTRAINT report_file_quarantine_copy_check;
ALTER TABLE report_file ADD CONSTRAINT report_file_quarantine_copy_check
    CHECK (pipeline_status NOT IN ('QUARANTINED', 'SCAN_SUBMITTED') OR quarantine_key IS NOT NULL
           OR deleted_at IS NOT NULL);

-- A deleted rescan is no longer live: the file it was to replace can be replaced again.
DROP INDEX report_file_one_live_rescan_idx;
CREATE UNIQUE INDEX report_file_one_live_rescan_idx ON report_file (replaces_id)
    WHERE replaces_id IS NOT NULL AND reject_code IS NULL AND deleted_at IS NULL;

-- finding_status_proposal.proposed_as gains FILE_DELETED: a Finding that deleting a report file leaves reported by no
-- file, and that has a proposal history (so it is not removed), is proposed Remediated for the Cyber Manager to review,
-- as a rescan proposes it (ADR 0029 item 5). Like a rescan's, the proposal is Remediated only and names, in
-- replaced_file_id, the file where the Finding was last reported: here the deleted one. Changing a CHECK changes no row,
-- so the append-only triggers are not involved.
ALTER TABLE finding_status_proposal DROP CONSTRAINT finding_status_proposal_proposed_as_check;
ALTER TABLE finding_status_proposal ADD CONSTRAINT finding_status_proposal_proposed_as_check
    CHECK (proposed_as IN ('SMO', 'RESCAN', 'FILE_DELETED') AND (proposed_as = 'SMO' OR status = 'REMEDIATED')
           AND (proposed_as = 'SMO') = (replaced_file_id IS NULL));
