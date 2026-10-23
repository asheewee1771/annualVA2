-- Overdue is a flag, not a state (ticket 26, stories 42 and 71, ADR 0033). The scheduled sweep sets it once a window has
-- passed without the next step, and records when; nothing locks.
--
-- The one rule of which window a state waits on, and when that window has passed. Every statement that sets, keeps or
-- clears the flag (the sweep, the start, every transition) asks these two functions; none writes the mapping itself.
--   current_window_due  the last day of the window the state waits on: Not Started and Draft the Submission Window,
--                       In Review and Returned for Rework the Review Window, Awaiting Endorsement the Quarter's last
--                       day; null for a final or frozen state, which waits on nothing
--   window_passed       whether that window has passed on this business date (Asia/Singapore): a window's last day is
--                       inside it; never for a state that waits on nothing
CREATE FUNCTION current_window_due(state text, submit_due date, review_due date, endorse_due date) RETURNS date
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT CASE state
        WHEN 'NOT_STARTED' THEN submit_due
        WHEN 'DRAFT' THEN submit_due
        WHEN 'IN_REVIEW' THEN review_due
        WHEN 'RETURNED_FOR_REWORK' THEN review_due
        WHEN 'AWAITING_ENDORSEMENT' THEN endorse_due
    END
$$;

CREATE FUNCTION window_passed(state text, submit_due date, review_due date, endorse_due date, today date) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS $$
    SELECT coalesce(current_window_due(state, submit_due, review_due, endorse_due) < today, false)
$$;

-- va_submission.overdue_since: when (UTC) the sweep set the flag; null while the flag is clear. The flag is derived from
-- it, so the two can never disagree and no statement writes the flag itself.
ALTER TABLE va_submission ADD COLUMN overdue_since timestamptz;
ALTER TABLE va_submission DROP COLUMN overdue;
ALTER TABLE va_submission ADD COLUMN overdue boolean GENERATED ALWAYS AS (overdue_since IS NOT NULL) STORED;

-- not_started_overdue: a System with no VA Submission in a VA Cycle (Not Started has no va_submission row) whose
-- Submission Window passed, flagged by the sweep at overdue_since (UTC). One row per System and Cycle: a second sweep
-- inserts nothing. A VA Submission started afterwards takes the flag and its time with it; the row stays, as the record
-- of when the System was flagged.
CREATE TABLE not_started_overdue (
    system_id     uuid        NOT NULL REFERENCES va_system (id),
    cycle_year    smallint    NOT NULL,
    overdue_since timestamptz NOT NULL,
    PRIMARY KEY (system_id, cycle_year)
);

-- The audit log learns the state a VA Submission, or a Not Started System, was in when the sweep flagged it: a fixed code.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version', 'state'] = '{}'::jsonb);
