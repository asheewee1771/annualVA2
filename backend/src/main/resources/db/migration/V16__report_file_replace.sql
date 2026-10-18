-- Replacing a Scan Report file with a rescan (ticket 21, story 39, ADR 0029): the SMO/Tech Lead uploads a rescan of one
-- parsed report file. It goes through the whole pipeline like any upload, and once it is parsed it is compared with the
-- file it replaces, by identity key and against that file's places only. The old file is kept, superseded, until its VA
-- Submission is Endorsed and the retention ticket (49) lists it for disposal.
--
-- report_file.replaces_id
--   the parsed report file this one is a rescan of; set when it is uploaded, for a machine-readable file only. While it is
--   on its way through the pipeline the old file is unchanged, and if it is rejected nothing changes at all.
-- report_file.superseded_by, superseded_at
--   the rescan that replaced this file, and when (UTC): set in the transaction that records the rescan as parsed and
--   moves the Findings, so no file is ever superseded without its Findings having moved, or the other way round. Only a
--   parsed file is superseded, and a superseded file no longer counts against its Scan Report's 20 files.
ALTER TABLE report_file
    ADD COLUMN replaces_id   uuid REFERENCES report_file (id),
    ADD COLUMN superseded_by uuid REFERENCES report_file (id),
    ADD COLUMN superseded_at timestamptz,
    ADD CONSTRAINT report_file_replaces_check
        CHECK (replaces_id IS NULL OR (kind = 'MACHINE_READABLE' AND replaces_id <> id)),
    ADD CONSTRAINT report_file_superseded_check
        CHECK ((superseded_by IS NULL) = (superseded_at IS NULL)
               AND (superseded_by IS NULL OR (pipeline_status = 'PARSED' AND superseded_by <> id)));

-- At most one rescan of a file is live at a time (on its way, or parsed): a second one is refused, and two requests
-- that race are settled here. A rejected rescan does not count, so the file can be replaced again.
CREATE UNIQUE INDEX report_file_one_live_rescan_idx ON report_file (replaces_id)
    WHERE replaces_id IS NOT NULL AND reject_code IS NULL;
-- A rescan supersedes one file.
CREATE UNIQUE INDEX report_file_superseded_by_idx ON report_file (superseded_by) WHERE superseded_by IS NOT NULL;

-- finding_status_proposal.proposed_as
--   who made the proposal: SMO (the SMO/Tech Lead, every proposal before this ticket) or RESCAN (a rescan no longer
--   reported the Finding anywhere, so it is proposed Remediated with the rescan as evidence, for the Cyber Manager to
--   review). Carry-over (Release 2) adds its own values. A rescan proposes Remediated only. Adding the column with a
--   default changes no row, so the append-only trigger of V14 is not involved; the default is then dropped, so every
--   later write names it.
-- finding_status_proposal.replaced_file_id
--   for a RESCAN proposal, and only for one: the file the rescan replaced, which is kept (superseded) and is where the
--   Finding was last reported, so the Cyber Manager can read and download it beside the proposal.
ALTER TABLE finding_status_proposal
    ADD COLUMN proposed_as text NOT NULL DEFAULT 'SMO',
    ADD COLUMN replaced_file_id uuid REFERENCES report_file (id),
    ADD CONSTRAINT finding_status_proposal_proposed_as_check
        CHECK (proposed_as IN ('SMO', 'RESCAN') AND (proposed_as <> 'RESCAN' OR status = 'REMEDIATED')
               AND (proposed_as = 'RESCAN') = (replaced_file_id IS NOT NULL));
ALTER TABLE finding_status_proposal ALTER COLUMN proposed_as DROP DEFAULT;

-- The audit log learns the file a rescan replaced, the rescan whose settlement made a proposal, and who a proposal was
-- made as: ids and a fixed code.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id'] = '{}'::jsonb);
