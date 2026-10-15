-- Confidence of a Burp Suite issue (ticket 17, stories 28 and 30): Burp says how sure it is that an issue is real (Certain,
-- Firm or Tentative), and a Tentative one is the kind a person may mark False Positive with a reason (ticket 30 builds that).
--
-- finding_occurrence.confidence
--   how sure the scanner said it was at this place. It is kept on the occurrence and not on the Finding, for the reason
--   did_not_complete is: one Finding can be read from several places and several files with different words, and a second
--   stored copy on the Finding could disagree with them. The Finding's confidence is the most sure of its places, worked out
--   when the list reads (a Finding is Tentative only if every place it was reported was). NULL for every format that has no
--   confidence (Dependency-Check, SCAP, Nessus) and for a Burp issue that gave none or one this scale does not know.
ALTER TABLE finding_occurrence
    ADD COLUMN confidence text,
    ADD CONSTRAINT finding_occurrence_confidence_check
        CHECK (confidence IS NULL OR confidence IN ('CERTAIN', 'FIRM', 'TENTATIVE'));
