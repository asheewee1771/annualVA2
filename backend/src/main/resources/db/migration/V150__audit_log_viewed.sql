-- Ticket 50 (Q11, IM8 lm-4, ADR 0057): a read of the audit log is itself an entry, AUDIT_LOG_VIEWED, with what it asked
-- for: the actor, action, System and VA Submission filtered on, the business dates and the page, as ids, codes and
-- numbers only. The CHECK is rebuilt from the union of every key added before it (the latest rebuild was V149) and the
-- seven new ones; it must sort after every migration that rebuilt it.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version', 'state', 'decision', 'endorsed_late', 'export_id',
                              'export_type', 'format', 'classification', 'cycle', 'quarter', 'exemption_id', 'basis',
                              'disposal_list_id',
                              'submissions', 'files', 'superseded_files', 'exports', 'systems',
                              'audit_entries',
                              'filter_actor', 'filter_action', 'filter_system', 'filter_submission', 'filter_from',
                              'filter_to', 'page'] = '{}'::jsonb);
