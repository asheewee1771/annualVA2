-- The VA Submission (story 19): one System's evidence package for one VA Cycle. This slice creates it in Draft and
-- records who started it; later slices add the transitions, the Scan Reports and the Findings that hang off it.
--
-- va_submission:
--   system_id, cycle_year   one VA Submission per System and VA Cycle, whatever its state: a second start is refused
--   quarter                 1 to 4: the Quarter the System was in when the submission started
--   state                   the nine states a row can be in. With Not Started, which has no row (a System with no VA
--                           Submission), they are the ten of the spec's state table. Each state arrives with the
--                           command that enters it (this slice: DRAFT only)
--   overdue, endorsed_late  the two flags; both are set by later slices (the Overdue sweep, endorsing after the
--                           Quarter's last day) and are false until then
--   submit_due, review_due, endorse_due
--                           the windows, copied from the Quarter when the submission starts and kept with it (business
--                           dates in Asia/Singapore); a System added mid-Quarter gets its own with the Release 2 changes
--   started_at, started_by  when (UTC) and by whom the SMO/Tech Lead started it
-- submission_participation: the segregation-of-duties record (CONTEXT.md): one row per person and step on a VA
-- Submission, written the first time they act at that step. Starting is the submitting step.
CREATE TABLE va_submission (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    system_id     uuid        NOT NULL REFERENCES va_system (id),
    cycle_year    smallint    NOT NULL,
    quarter       smallint    NOT NULL,
    state         text        NOT NULL,
    overdue       boolean     NOT NULL DEFAULT false,
    endorsed_late boolean     NOT NULL DEFAULT false,
    submit_due    date        NOT NULL,
    review_due    date        NOT NULL,
    endorse_due   date        NOT NULL,
    started_at    timestamptz NOT NULL,
    started_by    uuid        NOT NULL REFERENCES app_user (id),
    CONSTRAINT va_submission_system_cycle_key UNIQUE (system_id, cycle_year),
    CONSTRAINT va_submission_quarter_check CHECK (quarter BETWEEN 1 AND 4),
    CONSTRAINT va_submission_state_check
        CHECK (state IN ('DRAFT', 'IN_REVIEW', 'RETURNED_FOR_REWORK', 'AWAITING_ENDORSEMENT', 'ENDORSED',
                         'EXEMPTION_REQUESTED', 'EXEMPTED', 'WITHDRAWN', 'NOT_ENDORSED')),
    CONSTRAINT va_submission_windows_check CHECK (submit_due < review_due AND review_due < endorse_due)
);

CREATE TABLE submission_participation (
    submission_id uuid        NOT NULL REFERENCES va_submission (id),
    user_id       uuid        NOT NULL REFERENCES app_user (id),
    step          text        NOT NULL,
    first_at      timestamptz NOT NULL,
    PRIMARY KEY (submission_id, user_id, step),
    CONSTRAINT submission_participation_step_check CHECK (step IN ('SUBMIT', 'REVIEW', 'DECIDE'))
);
