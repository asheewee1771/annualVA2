-- Return for rework (ticket 30, story 55): the Cyber Manager returns a VA Submission In Review to its SMO/Tech Lead with
-- the Disputed Findings and not-applicable justifications attached, and what they confirmed stays confirmed.
--
-- submission_return: one return of one VA Submission.
--   step         the step that returned it: REVIEW (the Cyber Manager); ticket 35 adds the VA POC's
--   comment      what the person returning wrote: optional, 1 to 4000 characters once cleaned (IM8 as-1). Data: never
--                logged or audited
--   returned_by, returned_at
--                who (an id) and when (UTC)
-- submission_return_item: what was Disputed when it was returned, each named by the review that Disputed it, so the
-- SMO/Tech Lead and a later reader see exactly what the return was about even after the item is proposed or rewritten
-- again. A Finding is named with its review (finding_id, finding_review_id); a not-applicable justification with its
-- Scan Report and review (report_type, report_review_id).
-- Both are history: a return is never changed or removed. Retention (ticket 49) replaces the triggers.
CREATE TABLE submission_return (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq           bigint      GENERATED ALWAYS AS IDENTITY,
    submission_id uuid        NOT NULL REFERENCES va_submission (id),
    step          text        NOT NULL,
    comment       text,
    returned_by   uuid        NOT NULL REFERENCES app_user (id),
    returned_at   timestamptz NOT NULL,
    CONSTRAINT submission_return_step_check CHECK (step IN ('REVIEW')),
    CONSTRAINT submission_return_comment_check CHECK (comment IS NULL OR char_length(comment) BETWEEN 1 AND 4000)
);

CREATE INDEX submission_return_submission_idx ON submission_return (submission_id, seq);

CREATE TABLE submission_return_item (
    return_id         uuid NOT NULL REFERENCES submission_return (id),
    seq               bigint GENERATED ALWAYS AS IDENTITY,
    finding_id        uuid REFERENCES finding (id),
    finding_review_id uuid REFERENCES finding_review (id),
    report_type       text,
    report_review_id  uuid REFERENCES report_review (id),
    CONSTRAINT submission_return_item_kind_check
        CHECK ((finding_id IS NOT NULL AND finding_review_id IS NOT NULL AND report_type IS NULL
                AND report_review_id IS NULL)
               OR (finding_id IS NULL AND finding_review_id IS NULL AND report_type IS NOT NULL
                   AND report_review_id IS NOT NULL)),
    CONSTRAINT submission_return_item_report_type_check
        CHECK (report_type IS NULL OR report_type IN ('DEPENDENCY_CHECK', 'SECURE_CONFIGURATION', 'BURP_SUITE'))
);

CREATE INDEX submission_return_item_return_idx ON submission_return_item (return_id, seq);

-- Never changed or removed, per statement, as the reviews (V21) and the Declarations (V19).
CREATE TRIGGER submission_return_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON submission_return
    FOR EACH STATEMENT EXECUTE FUNCTION declaration_append_only();

CREATE TRIGGER submission_return_item_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON submission_return_item
    FOR EACH STATEMENT EXECUTE FUNCTION declaration_append_only();
