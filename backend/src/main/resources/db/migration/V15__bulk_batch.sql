-- Bulk-set batches (ticket 20, story 37, ADR 0028): the SMO/Tech Lead applies one Finding Status, with its fields, to
-- several Findings in one step. The step is one batch, and each Finding gets an ordinary proposal (V14) that names it, so
-- the Cyber Manager can later confirm or Dispute the batch as a whole (ticket 29).
--
-- bulk_batch: one Bulk-set step.
--   submission_id    the VA Submission every Finding of the batch belongs to
--   proposed_by, proposed_at
--                    who (an id) and when (UTC); the same as on each of its proposals
-- What was proposed (the status and its fields) is on the proposals, not repeated here.
CREATE TABLE bulk_batch (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq           bigint      GENERATED ALWAYS AS IDENTITY,
    submission_id uuid        NOT NULL REFERENCES va_submission (id),
    proposed_by   uuid        NOT NULL REFERENCES app_user (id),
    proposed_at   timestamptz NOT NULL
);

CREATE INDEX bulk_batch_submission_idx ON bulk_batch (submission_id, seq);

-- A batch is history, like its proposals: never changed or removed. Per statement, as for the proposals (V14) and the
-- audit log (V5). Retention (ticket 49) replaces the trigger by a later migration, deliberately.
CREATE FUNCTION bulk_batch_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'bulk_batch is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER bulk_batch_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON bulk_batch
    FOR EACH STATEMENT EXECUTE FUNCTION bulk_batch_append_only();

-- A proposal made in a batch names it; one made for a single Finding has none. Adding a nullable column changes no row,
-- so the append-only trigger of V14 is not involved.
ALTER TABLE finding_status_proposal ADD COLUMN batch_id uuid REFERENCES bulk_batch (id);

CREATE INDEX finding_status_proposal_batch_idx ON finding_status_proposal (batch_id) WHERE batch_id IS NOT NULL;
