-- Role grants: the only source of roles (never token claims). A row says that a person, known by email and
-- by user id once they have signed in, holds a role, for one System where the role is per System.
--   role          SMO and CYBER_MANAGER are per System, VA_POC is organisation-wide, AUDITOR is scoped by its own
--                 scope rows (added with the Auditor slice)
--   user_id       null until the person has signed in: a grant made by email attaches at the first sign-in
--   system_id     the System the role is held for; no foreign key yet, the System Register table arrives with the
--                 Register apply slice and adds it
--   source        CONFIG (the two VA POCs, from deployment configuration), REGISTER (System Register apply) or
--                 GRANT (a VA POC granting an Auditor)
--   expires_at    Auditor grants only
--   last_used_at  touched at most once a day; drives the 90-day lapse
--   lapsed_at, suspended_at, revoked_at   why a grant stopped counting; any of them set means no authority
CREATE TABLE role_grant (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    role         text        NOT NULL,
    user_id      uuid        REFERENCES app_user (id),
    email        citext      NOT NULL,
    system_id    uuid,
    source       text        NOT NULL,
    granted_at   timestamptz NOT NULL,
    granted_by   uuid        REFERENCES app_user (id),
    expires_at   timestamptz,
    last_used_at timestamptz,
    lapsed_at    timestamptz,
    suspended_at timestamptz,
    revoked_at   timestamptz,
    CONSTRAINT role_grant_role_check   CHECK (role IN ('SMO', 'CYBER_MANAGER', 'VA_POC', 'AUDITOR')),
    CONSTRAINT role_grant_source_check CHECK (source IN ('CONFIG', 'REGISTER', 'GRANT')),
    CONSTRAINT role_grant_system_check CHECK ((role IN ('SMO', 'CYBER_MANAGER')) = (system_id IS NOT NULL)
                                              OR role = 'AUDITOR'),
    -- The highest-privilege role comes only from deployment configuration (story 75), by construction.
    CONSTRAINT role_grant_va_poc_check CHECK (role <> 'VA_POC' OR source = 'CONFIG')
);

-- One live grant per role, person and System; a revoked grant may be granted again as a new row.
CREATE UNIQUE INDEX role_grant_live_key
    ON role_grant (role, email, COALESCE(system_id, '00000000-0000-0000-0000-000000000000'::uuid))
    WHERE revoked_at IS NULL;

-- The per-request lookup: a person's grants that still count.
CREATE INDEX role_grant_user_idx ON role_grant (user_id) WHERE revoked_at IS NULL;

-- First sign-in attaches the grants made by email.
CREATE INDEX role_grant_unattached_idx ON role_grant (email) WHERE user_id IS NULL AND revoked_at IS NULL;
