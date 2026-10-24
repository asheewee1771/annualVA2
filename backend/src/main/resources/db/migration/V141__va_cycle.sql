-- The VA Cycle lifecycle (ticket 41, stories 17 and 68, ADR 0044): a Cycle opens when its Q1 starts and closes 4 weeks
-- after its Q4 ends, or earlier when a VA POC closes it; the close makes every VA Submission that is neither Endorsed
-- nor Exempted, and every System with none, Not Endorsed. The calendar itself stays computed (ADR 0015); only what
-- cannot be derived is stored.
--
-- va_cycle: one row per VA Cycle the lifecycle job opened (written by the cycle module).
--   opened_at   when (UTC) the job, or the close, recorded that the Cycle's Q1 had started
--   closes_on   an early close's business date (Asia/Singapore), overriding the default (Q4's end plus 28 days);
--               null while the default stands
--   closed_at   when (UTC) the Cycle was closed; null while it is open
--   closed_by   the VA POC who closed it; null for the lifecycle job's close after closes_on, which acts for nobody
CREATE TABLE va_cycle (
    year      smallint    PRIMARY KEY,
    opened_at timestamptz NOT NULL,
    closes_on date,
    closed_at timestamptz,
    closed_by uuid        REFERENCES app_user (id),
    CONSTRAINT va_cycle_year_check CHECK (year BETWEEN 2000 AND 2099),
    CONSTRAINT va_cycle_closed_check CHECK (closed_at IS NOT NULL OR (closes_on IS NULL AND closed_by IS NULL))
);

-- The System Register as a Cycle saw it, once a newer Cycle has opened (written by the register module). The Register is
-- one standing list whose Quarter column applies to every Cycle; while two Cycles overlap an apply changes only the
-- newer one, so the older Cycle's Systems and their Quarters are kept here from the moment the newer one opens.
--   cycle_register         a Cycle whose Systems are kept, and when they were taken
--   cycle_register_system  each System that was active then, with its Quarter in that Cycle
CREATE TABLE cycle_register (
    cycle_year smallint    PRIMARY KEY,
    frozen_at  timestamptz NOT NULL
);

CREATE TABLE cycle_register_system (
    cycle_year smallint NOT NULL REFERENCES cycle_register (cycle_year),
    system_id  uuid     NOT NULL REFERENCES va_system (id),
    quarter    smallint NOT NULL,
    PRIMARY KEY (cycle_year, system_id),
    CONSTRAINT cycle_register_system_quarter_check CHECK (quarter BETWEEN 1 AND 4)
);

-- A System still Not Started when its Cycle closes gets a VA Submission row of its own, Not Endorsed, that nobody
-- started: started_at and started_by are null on such a row and on no other.
ALTER TABLE va_submission ALTER COLUMN started_at DROP NOT NULL;
ALTER TABLE va_submission ALTER COLUMN started_by DROP NOT NULL;
ALTER TABLE va_submission ADD CONSTRAINT va_submission_started_check
    CHECK ((started_at IS NULL) = (started_by IS NULL) AND (started_at IS NOT NULL OR state = 'NOT_ENDORSED'));

-- The audit log learns the VA Cycle an entry is about: a year, never a date.
ALTER TABLE audit_entry DROP CONSTRAINT audit_entry_detail_check;
ALTER TABLE audit_entry ADD CONSTRAINT audit_entry_detail_check
    CHECK (jsonb_typeof(detail) = 'object'
           AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                              'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id',
                              'preview_id', 'added', 'updated', 'retired', 'unchanged', 'roles_granted',
                              'roles_revoked', 'finding_status', 'replaced_file_id', 'proposed_as',
                              'rescan_file_id', 'report_type', 'mode', 'date_changed', 'target_changed',
                              'declaration_version', 'state', 'decision', 'endorsed_late', 'cycle'] = '{}'::jsonb);

-- The notifications (V136) learn the close's notice to the SMO/Tech Lead: their System is Not Endorsed.
ALTER TABLE notification DROP CONSTRAINT notification_action_check;
ALTER TABLE notification ADD CONSTRAINT notification_action_check CHECK (action IN ('REVIEW', 'REWORK', 'ENDORSE',
    'SUBMIT_OVERDUE', 'REVIEW_OVERDUE', 'ENDORSE_OVERDUE', 'SYSTEM_MOVED', 'SYSTEM_TRANSFERRED_TO_YOU',
    'SYSTEM_TRANSFERRED_FROM_YOU', 'NOT_ENDORSED'));
