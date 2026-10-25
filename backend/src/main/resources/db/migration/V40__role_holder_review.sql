-- Role-holder review, suspension and identity-provider disablement (ticket 47, story 80a, IM8 ac-3 and ac-4, ADR 0056).
-- Runs after V39, which changed role_grant too.
--
-- role_grant: V4 already holds when a grant was suspended; a suspension now also records who suspended it and why.
--   suspended_by      the VA POC whose review suspended it; null when the identity provider reported the person disabled
--   suspension_cause  REVIEW (a VA POC's role-holder review) or IDENTITY_PROVIDER (the person is disabled there)
ALTER TABLE role_grant
    ADD COLUMN suspended_by     uuid REFERENCES app_user (id),
    ADD COLUMN suspension_cause text;

-- A grant suspended before this migration (none in a deployed database) counts as suspended by a review.
UPDATE role_grant SET suspension_cause = 'REVIEW' WHERE suspended_at IS NOT NULL;

ALTER TABLE role_grant
    ADD CONSTRAINT role_grant_suspension_cause_check CHECK (suspension_cause IN ('REVIEW', 'IDENTITY_PROVIDER')),
    ADD CONSTRAINT role_grant_suspended_check CHECK ((suspended_at IS NULL) = (suspension_cause IS NULL));

-- role_holder_review: each completed review of every role holder against the System Register (ac-4: at each Register
-- upload and at least every 3 months). The audit log records it too (ROLE_HOLDERS_REVIEWED); this row is what the review
-- screen reads to say when the next one is due.
--   reviewed_by  the VA POC who recorded it
--   holders      how many role holders the review listed
CREATE TABLE role_holder_review (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    reviewed_at timestamptz NOT NULL,
    reviewed_by uuid        NOT NULL REFERENCES app_user (id),
    holders     integer     NOT NULL,
    CONSTRAINT role_holder_review_holders_check CHECK (holders >= 0)
);

CREATE INDEX role_holder_review_latest_idx ON role_holder_review (reviewed_at DESC);
