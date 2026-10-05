-- The audit log (IM8 lm-4, lm-15, lm-19; story 84): one row per sign-in, sign-out, refused request and,
-- from now on, per action. Append-only: a trigger refuses UPDATE, DELETE and TRUNCATE for every
-- connection, including the application's own. Retention (Cycle plus 3 years, ticket 49) replaces the
-- trigger by a later migration, deliberately; nothing in the application can switch it off.
--   seq             the order entries were written in: breaks the tie between entries with the same occurred_at, so
--                   "newest first" is deterministic. Never shown; id is the public identifier
--   occurred_at     when it happened, by the application clock (UTC)
--   actor_id        the app_user who acted; null for an anonymous request, a refused sign-in or the system.
--                   No foreign key: an entry is written in its own transaction and must never fail on the user
--   actor_role      the roles the actor held at the time, highest privilege first, comma-separated; null if none
--   action          what happened (the AuditAction enum in the audit module)
--   outcome         SUCCESS, REFUSED (the rules said no) or FAILED (something went wrong)
--   system_id, submission_id, finding_id
--                   what it was about, as ids only: an entry never copies Finding detail
--   correlation_id  the request's correlation id, tying the entry to the application log
--   detail          an object whose keys are on the allow-list below: fixed codes, ids and counts, never a CVE
--                   id, rule name, host, comment or justification text
CREATE TABLE audit_entry (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    seq            bigint      GENERATED ALWAYS AS IDENTITY,
    occurred_at    timestamptz NOT NULL,
    actor_id       uuid,
    actor_role     text,
    action         text        NOT NULL,
    outcome        text        NOT NULL,
    system_id      uuid,
    submission_id  uuid,
    finding_id     uuid,
    correlation_id text        NOT NULL,
    detail         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT audit_entry_action_check
        CHECK (action ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT audit_entry_outcome_check
        CHECK (outcome IN ('SUCCESS', 'REFUSED', 'FAILED')),
    CONSTRAINT audit_entry_actor_role_check
        CHECK (actor_role IS NULL OR actor_role ~ '^[A-Z_]+(,[A-Z_]+)*$'),
    CONSTRAINT audit_entry_correlation_check
        CHECK (correlation_id ~ '^[A-Za-z0-9._-]{1,64}$'),
    -- The allow-list, mirrored by DetailKey in the audit module. A new key is added here on purpose.
    CONSTRAINT audit_entry_detail_check
        CHECK (jsonb_typeof(detail) = 'object'
               AND detail - ARRAY['state_from', 'state_to', 'batch_id', 'count', 'file_id', 'reject_code',
                                  'reason', 'intent', 'status', 'error_code', 'role', 'source', 'grant_id'] = '{}'::jsonb)
);

CREATE INDEX audit_entry_occurred_idx ON audit_entry (occurred_at DESC, seq DESC);
CREATE INDEX audit_entry_actor_idx ON audit_entry (actor_id, occurred_at DESC) WHERE actor_id IS NOT NULL;
CREATE INDEX audit_entry_action_idx ON audit_entry (action, occurred_at DESC);
CREATE INDEX audit_entry_system_idx ON audit_entry (system_id, occurred_at DESC) WHERE system_id IS NOT NULL;
CREATE INDEX audit_entry_submission_idx ON audit_entry (submission_id, occurred_at DESC) WHERE submission_id IS NOT NULL;

CREATE FUNCTION audit_entry_append_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_entry is append-only: % is not allowed', TG_OP;
END;
$$;

-- Per statement, not per row: a statement that matches no row is refused too.
CREATE TRIGGER audit_entry_append_only
    BEFORE UPDATE OR DELETE OR TRUNCATE ON audit_entry
    FOR EACH STATEMENT EXECUTE FUNCTION audit_entry_append_only();
