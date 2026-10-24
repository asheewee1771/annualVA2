-- A VA POC endorses a VA Submission Awaiting Endorsement (ticket 34, stories 65, 67 and 70, ADR 0040).
--
-- submission_participation learns the endorsing step, ENDORSE: a VA POC who endorses (and, with tickets 35 and 40, who
-- returns or decides an Exemption) on a VA Submission. A VA POC counts at it only once they act; no Register assignment
-- puts anyone there (ADR 0039 item 5). DECIDE stays allowed and unused, for ticket 40 to keep or drop.
--
-- va_submission.endorsed_late and endorsed_at (V8, V133) are written by the endorse command; nothing else is new there.
ALTER TABLE submission_participation DROP CONSTRAINT submission_participation_step_check;
ALTER TABLE submission_participation ADD CONSTRAINT submission_participation_step_check
    CHECK (step IN ('SUBMIT', 'REVIEW', 'ENDORSE', 'DECIDE'));

-- The audit log learns whether an endorsement was late: a flag, never a date.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version', 'state', 'decision', 'endorsed_late'] = '{}'::jsonb);
