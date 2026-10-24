-- The review engine, second part (ticket 29, story 51): the Cyber Manager confirms or Disputes a Bulk-set batch (V15) as a
-- whole. A batch review is one step and an ordinary review (V21) of each member it decided, as a Bulk-set is one batch and
-- an ordinary proposal per Finding (ADR 0028 item 1).
--
-- batch_review: one review of a batch.
--   batch_id      the batch reviewed
--   reviewed_by, reviewed_at
--                 who (an id) and when (UTC); the same as on each of its Findings' reviews
-- What was decided (the decision and the comment) is on the Findings' reviews, not repeated here. A member whose current
-- proposal was no longer the batch's, or was already reviewed, has no review from it.
CREATE TABLE batch_review (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq         bigint      GENERATED ALWAYS AS IDENTITY,
    batch_id    uuid        NOT NULL REFERENCES bulk_batch (id),
    reviewed_by uuid        NOT NULL REFERENCES app_user (id),
    reviewed_at timestamptz NOT NULL
);

CREATE INDEX batch_review_batch_idx ON batch_review (batch_id, seq);

-- History, like the reviews: never changed or removed, per statement (V21). Retention (ticket 49) replaces the trigger by
-- a later migration, deliberately, deleting the Findings' reviews before the batch reviews.
CREATE TRIGGER batch_review_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON batch_review
    FOR EACH STATEMENT EXECUTE FUNCTION declaration_append_only();

-- A review made in a batch review names it; one made for a single Finding has none. Adding a nullable column changes no
-- row, so the append-only trigger of V21 is not involved.
ALTER TABLE finding_review ADD COLUMN batch_review_id uuid REFERENCES batch_review (id);

CREATE INDEX finding_review_batch_review_idx ON finding_review (batch_review_id) WHERE batch_review_id IS NOT NULL;
