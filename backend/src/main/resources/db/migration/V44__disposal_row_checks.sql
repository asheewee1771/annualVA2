-- Per-row disposal checks on every append-only history table (ticket 62, story 87, ADR 0050 item 7).
--
-- V36 opened the append-only tables to a DELETE inside a transaction that names an APPROVED disposal list, and checked
-- only the audit log row by row (an entry before the list's cutoff). The other history tables were open whole while the
-- list waited for the executor: a transaction naming the list could delete any of their rows. From here on a row of
-- those tables is deleted only when its VA Submission is one the running APPROVED list names (a SUBMISSION item), so a
-- statement of the executor that reached another VA Submission's history fails. What the check trusts, and the ticket
-- that tracks it, are in ADR 0050 item 7.
--
-- Every history table hangs off exactly one VA Submission, through a foreign key, so a row of another VA Submission is
-- a row of another System or VA Cycle too. The disposal deletes a row before the row it hangs off
-- (DisposalStore.STEPS), so the parent is still there to read.
--
-- The statement-level triggers of V36 are unchanged and fire first: an UPDATE or a TRUNCATE is refused always, a DELETE
-- outside an APPROVED list's transaction is refused whole, and declaration_text is never opened (it gets no row check).
-- audit_entry keeps its own row check, audit_entry_disposal_range (V36).

-- A row check reads one list's items by kind and target for every row it lets go.
CREATE INDEX disposal_item_target_idx ON disposal_item (list_id, kind, target_id);

-- Refuses the row unless its VA Submission is on the APPROVED list this transaction executes. A table this function does
-- not know is refused too: a new history table names here how its rows reach their VA Submission (or gets its own check).
CREATE FUNCTION history_row_disposal_listed() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    submission uuid;
BEGIN
    CASE TG_TABLE_NAME
        WHEN 'bulk_batch', 'declaration', 'report_review', 'submission_return' THEN
            submission := OLD.submission_id;
        WHEN 'finding_status_proposal', 'finding_review' THEN
            SELECT submission_id INTO submission FROM finding WHERE id = OLD.finding_id;
        WHEN 'batch_review' THEN
            SELECT submission_id INTO submission FROM bulk_batch WHERE id = OLD.batch_id;
        WHEN 'submission_return_item' THEN
            SELECT submission_id INTO submission FROM submission_return WHERE id = OLD.return_id;
        ELSE
            RAISE EXCEPTION '% is append-only: no disposal check knows its rows', TG_TABLE_NAME;
    END CASE;
    IF NOT EXISTS (SELECT 1 FROM disposal_item
                   WHERE list_id = disposal_running() AND kind = 'SUBMISSION' AND target_id = submission) THEN
        RAISE EXCEPTION '% is append-only: a row outside the approved disposal list is kept', TG_TABLE_NAME;
    END IF;
    RETURN OLD;
END;
$$;

CREATE TRIGGER finding_status_proposal_disposal_listed
    BEFORE DELETE ON finding_status_proposal
    FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed();

CREATE TRIGGER bulk_batch_disposal_listed
    BEFORE DELETE ON bulk_batch
    FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed();

CREATE TRIGGER declaration_disposal_listed
    BEFORE DELETE ON declaration
    FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed();

CREATE TRIGGER finding_review_disposal_listed
    BEFORE DELETE ON finding_review
    FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed();

CREATE TRIGGER report_review_disposal_listed
    BEFORE DELETE ON report_review
    FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed();

CREATE TRIGGER batch_review_disposal_listed
    BEFORE DELETE ON batch_review
    FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed();

CREATE TRIGGER submission_return_disposal_listed
    BEFORE DELETE ON submission_return
    FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed();

CREATE TRIGGER submission_return_item_disposal_listed
    BEFORE DELETE ON submission_return_item
    FOR EACH ROW EXECUTE FUNCTION history_row_disposal_listed();
