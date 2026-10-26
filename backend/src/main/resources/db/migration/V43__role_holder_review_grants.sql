-- A role-holder review records which holders it checked (ADR 0056 item 4, decided 2026-10-26,
-- docs/agents/reviewer-decisions.md): the ids of the grants the VA POC reviewed, as the screen listed them. The count
-- V40 keeps (holders) is their number. Reviews recorded before this migration checked holders this column cannot name.
ALTER TABLE role_holder_review ADD COLUMN grant_ids uuid[] NOT NULL DEFAULT '{}';
