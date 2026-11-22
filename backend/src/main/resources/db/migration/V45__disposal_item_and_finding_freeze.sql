-- Freezes the two things V44's per-row disposal check reads besides the list's own row (ticket 66, story 87): a list's
-- items and a Finding's VA Submission. Whatever the transaction names in annualva.disposal_list, an item is only ever
-- inserted, into a DRAFT list, and a Finding never changes VA Submission. What the check still trusts: ADR 0050 item 7.

-- The planner's insert for a new DRAFT list (DisposalStore.insert) is the items' only writer. FOR SHARE, not the foreign
-- key's FOR KEY SHARE, because only FOR SHARE conflicts with an approval's status UPDATE: an insert waits for an
-- approval of the same list running at the same moment and then reads its new status.
CREATE FUNCTION disposal_item_insert_while_draft() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM disposal_list WHERE id = NEW.list_id AND status = 'DRAFT' FOR SHARE;
        IF FOUND THEN
            RETURN NEW;
        END IF;
        RAISE EXCEPTION 'disposal_item: an item is inserted only into a DRAFT list';
    END IF;
    RAISE EXCEPTION 'disposal_item is insert-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER disposal_item_insert_while_draft
    BEFORE INSERT OR UPDATE OR DELETE ON disposal_item
    FOR EACH ROW EXECUTE FUNCTION disposal_item_insert_while_draft();

CREATE TRIGGER disposal_item_no_truncate
    BEFORE TRUNCATE ON disposal_item
    FOR EACH STATEMENT EXECUTE FUNCTION disposal_item_insert_while_draft();

-- The application's updates of a Finding (its current proposal, review state and severity) never name another VA
-- Submission; one that would is refused.
CREATE FUNCTION finding_submission_fixed() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'finding: a Finding stays with the VA Submission it was found in';
END;
$$;

CREATE TRIGGER finding_submission_fixed
    BEFORE UPDATE ON finding
    FOR EACH ROW WHEN (OLD.submission_id IS DISTINCT FROM NEW.submission_id)
    EXECUTE FUNCTION finding_submission_fixed();
