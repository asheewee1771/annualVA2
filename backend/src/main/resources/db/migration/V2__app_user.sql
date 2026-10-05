-- Users: one row per person who has signed in or has been pre-registered by email (System Register).
--   sso_id          the MCC SSO afcas.uuid once the person has signed in; null while only pre-registered
--   email           the verified email (citext: case-insensitive, unique)
--   linked_at       when the first sign-in linked the row to an sso_id
--   idp_disabled_at set when the identity provider reports the person disabled (access ends immediately)
CREATE TABLE app_user (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    email           citext      NOT NULL,
    sso_id          text,
    display_name    text,
    created_at      timestamptz NOT NULL,
    linked_at       timestamptz,
    idp_disabled_at timestamptz,
    CONSTRAINT app_user_email_key  UNIQUE (email),
    CONSTRAINT app_user_sso_id_key UNIQUE (sso_id)
);
