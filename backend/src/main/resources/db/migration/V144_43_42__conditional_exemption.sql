-- Conditional Exemptions (ticket 42, story 47, ADR 0050): an approved Exemption resting on a scheduled VA stays
-- conditional until its SMO/Tech Lead records that the VA happened and a VA POC confirms it; if nothing is recorded by
-- lapses_on (the Quarter's last day plus 3 months) the lapse job puts the VA Submission back where the request froze it,
-- with new windows, or makes it Not Endorsed once its VA Cycle has closed.
--
-- Numbered 144.43.42, not V142: it rebuilds the notification CHECKs that V144_40 (ticket 40) and V144_43 (ticket 43)
-- rebuilt last, so it must run after them.
--
-- exemption gains, each written once:
--   va_happened_on   the business date (Asia/Singapore) the SMO/Tech Lead says the scheduled VA happened
--   va_evidence      what they wrote about it: cleaned by the one text rule, never logged or audited
--   va_recorded_by/at who recorded it and when; from then on the Exemption no longer lapses
--   confirmed_by/at  the VA POC who confirmed the recorded VA, and when: the Exemption is no longer conditional
--   lapsed_at        when the lapse job lapsed it (nothing was recorded by lapses_on)
ALTER TABLE exemption
    ADD COLUMN va_happened_on date,
    ADD COLUMN va_evidence    text,
    ADD COLUMN va_recorded_by uuid REFERENCES app_user (id),
    ADD COLUMN va_recorded_at timestamptz,
    ADD COLUMN confirmed_by   uuid REFERENCES app_user (id),
    ADD COLUMN confirmed_at   timestamptz,
    ADD COLUMN lapsed_at      timestamptz,
    ADD CONSTRAINT exemption_va_recorded_check CHECK ((va_recorded_at IS NULL) = (va_happened_on IS NULL)
                                                      AND (va_recorded_at IS NULL) = (va_evidence IS NULL)
                                                      AND (va_recorded_at IS NULL) = (va_recorded_by IS NULL)),
    ADD CONSTRAINT exemption_va_evidence_check CHECK (length(va_evidence) BETWEEN 1 AND 4000),
    ADD CONSTRAINT exemption_confirmed_check CHECK ((confirmed_at IS NULL) = (confirmed_by IS NULL)
                                                    AND (confirmed_at IS NULL OR va_recorded_at IS NOT NULL)),
    -- Only a conditional Exemption (it has a lapse date) is recorded, confirmed or lapsed, and a recorded one never lapses.
    ADD CONSTRAINT exemption_conditional_check CHECK (lapses_on IS NOT NULL
                                                      OR (va_recorded_at IS NULL AND lapsed_at IS NULL)),
    ADD CONSTRAINT exemption_lapsed_check CHECK (lapsed_at IS NULL OR va_recorded_at IS NULL);

-- What the lapse job looks for: a conditional Exemption with nothing recorded and not lapsed yet.
CREATE INDEX exemption_lapsing_idx ON exemption (lapses_on)
    WHERE lapses_on IS NOT NULL AND va_recorded_at IS NULL AND lapsed_at IS NULL;

-- The notifications (V136, V141, V141_35, V144_40, V144_43) learn the recorded VA to both VA POCs, as a pair that clears once
-- either confirms it; the confirmation to the SMO/Tech Lead; and the lapse to the SMO/Tech Lead, back where it was or Not
-- Endorsed.
ALTER TABLE notification DROP CONSTRAINT notification_action_check;
ALTER TABLE notification ADD CONSTRAINT notification_action_check CHECK (action IN ('REVIEW', 'REWORK', 'ENDORSE',
    'SUBMIT_OVERDUE', 'REVIEW_OVERDUE', 'ENDORSE_OVERDUE', 'SYSTEM_MOVED', 'SYSTEM_TRANSFERRED_TO_YOU',
    'SYSTEM_TRANSFERRED_FROM_YOU', 'NOT_ENDORSED', 'VA_POC_REWORK', 'VA_POC_RETURNED', 'DECIDE_EXEMPTION',
    'EXEMPTION_APPROVED', 'EXEMPTION_REJECTED', 'WITHDRAWN', 'CONFIRM_VA', 'VA_CONFIRMED', 'EXEMPTION_LAPSED',
    'EXEMPTION_LAPSED_NOT_ENDORSED'));
ALTER TABLE notification DROP CONSTRAINT notification_awaits_check;
ALTER TABLE notification ADD CONSTRAINT notification_awaits_check CHECK (awaits IN ('ENDORSEMENT', 'EXEMPTION',
    'VA_CONFIRMATION'));
