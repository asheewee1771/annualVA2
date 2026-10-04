-- Baseline: extensions every later migration relies on.
--   citext     case-insensitive emails (System Register, sign-in linking)
--   pgcrypto   gen_random_uuid() on older servers; built in from PostgreSQL 13 but harmless to declare
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
