-- A VA POC publishes each new version of the Declaration text in the app (ticket 61, the decision of 2026-10-24 in
-- docs/agents/reviewer-decisions.md), instead of a migration adding it (V19's note no longer holds). A version is still
-- one row, inserted and never changed or removed (the append-only trigger of V19, as V36 rebuilt it, is unchanged), and
-- the current version is still the highest. Each version now says who published it and when.
--   published_by  the VA POC who published it; null for the placeholder V19 seeded with the app
--   published_at  when it was published, and so became current; null as published_by is
-- Adding the columns rewrites no row, so the trigger that refuses UPDATE is not involved.
ALTER TABLE declaration_text
    ADD COLUMN published_by uuid REFERENCES app_user (id),
    ADD COLUMN published_at timestamptz,
    ADD CONSTRAINT declaration_text_published_check CHECK ((published_by IS NULL) = (published_at IS NULL));

-- The count of VA Submissions that accepted each version is read by version.
CREATE INDEX declaration_version_idx ON declaration (version);
