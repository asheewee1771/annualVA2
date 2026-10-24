-- Exports, generated in the background and then downloaded (ticket 44, stories 81 and 86, ADR 0043). The first is the
-- management summary in Excel; the PDF (ticket 45) and the other exports add their own codes to the checks below.
--
-- export_request: one export a person asked for.
--   requested_by    the person who asked; only they read its status and download it
--   requested_at    when, by the application clock (UTC)
--   export_type     what it is: MGMT_SUMMARY
--   format          the file it is made as: XLSX
--   cycle, quarter  the VA Cycle it covers, and the Quarter when it covers one Quarter only (null for the whole Cycle)
--   classification  CONFIDENTIAL when it covers the complete set (all Systems of the Quarter or Cycle), otherwise
--                   RESTRICTED (Restricted / Sensitive-Normal); assumption A-1
--   system_count    how many Systems it lists
--   content         what the file will hold, read with the requester's access when they asked: counts, System IDs and
--                   names, SMO/Tech Lead emails, states and flags, never a Finding. Kept only while the export is
--                   QUEUED: it is removed once the file is made or the export fails
--   status          QUEUED, then READY or FAILED; READY becomes EXPIRED 24 hours after the file was made, when the
--                   export job removes the file
--   file_key        the key of the file in the file store's export zone (encrypted at rest), while it is READY
--   ready_at        when the file was made
--   expires_at      24 hours after ready_at; the file is not served from then on
CREATE TABLE export_request (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    requested_by   uuid        NOT NULL REFERENCES app_user (id),
    requested_at   timestamptz NOT NULL,
    export_type    text        NOT NULL CHECK (export_type IN ('MGMT_SUMMARY')),
    format         text        NOT NULL CHECK (format IN ('XLSX')),
    cycle          integer     NOT NULL,
    quarter        integer     CHECK (quarter BETWEEN 1 AND 4),
    classification text        NOT NULL CHECK (classification IN ('CONFIDENTIAL', 'RESTRICTED')),
    system_count   integer     NOT NULL CHECK (system_count >= 0),
    content        jsonb,
    status         text        NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'READY', 'FAILED', 'EXPIRED')),
    file_key       text,
    ready_at       timestamptz,
    expires_at     timestamptz,
    CONSTRAINT export_request_content_check CHECK ((status = 'QUEUED') = (content IS NOT NULL)),
    CONSTRAINT export_request_file_check CHECK ((status = 'READY') = (file_key IS NOT NULL)),
    CONSTRAINT export_request_ready_check
        CHECK ((status IN ('READY', 'EXPIRED')) = (ready_at IS NOT NULL AND expires_at IS NOT NULL))
);

CREATE INDEX export_request_requester_idx ON export_request (requested_by, requested_at DESC);
CREATE INDEX export_request_open_idx ON export_request (status, requested_at) WHERE status IN ('QUEUED', 'READY');

-- The audit log learns which export an entry is about and what it was: ids, fixed codes and numbers only.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version', 'state', 'decision', 'endorsed_late', 'export_id',
                              'export_type', 'format', 'classification', 'cycle', 'quarter'] = '{}'::jsonb);
