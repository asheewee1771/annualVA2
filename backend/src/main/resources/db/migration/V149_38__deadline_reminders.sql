-- Deadline reminders (ticket 38, stories 88, 89 and 90, ADR 0051). A planner reminds whoever's step it is before and on
-- the last day of the window their work waits on (V20 current_window_due), then weekly while it is Overdue with a copy to
-- the next person up, and reminds both VA POCs 2 weeks and 1 week before the Quarter's last day. A reminder is a
-- notification like any other (V136) and is mailed through the outbox (V137). Numbered after V149 and V144_43 (which
-- rebuilt notification_action_check last); the rebuild lists every action allowed before.
--
-- notification.reminder_on: for a reminder, the business day it is for; null for the notice of a move. A person gets at
-- most one reminder with the same action, System, due date and day, so a later pass of the planner, or a second
-- instance's pass, writes and mails nothing more. A reminder always names the due date it reminds of.
ALTER TABLE notification ADD COLUMN reminder_on date;
ALTER TABLE notification ADD CONSTRAINT notification_reminder_due_check CHECK (reminder_on IS NULL OR due_on IS NOT NULL);
CREATE UNIQUE INDEX notification_reminder_once_idx ON notification (recipient_id, action, system_id, due_on, reminder_on)
    WHERE reminder_on IS NOT NULL;

-- The reminders' actions:
--   SUBMIT_DUE_SOON, SUBMIT_DUE_TODAY, SUBMIT_STILL_OVERDUE  to the SMO/Tech Lead (Not Started, Draft, Returned for Rework)
--   SUBMIT_OVERDUE_COPY                                       the Cyber Manager's copy of the weekly one
--   REVIEW_DUE_SOON, REVIEW_DUE_TODAY, REVIEW_STILL_OVERDUE  to the Cyber Manager (In Review)
--   REVIEW_OVERDUE_COPY                                       each VA POC's copy of the weekly one
--   ENDORSE_DUE_IN_TWO_WEEKS, ENDORSE_DUE_IN_ONE_WEEK         to both VA POCs (Awaiting Endorsement)
ALTER TABLE notification DROP CONSTRAINT notification_action_check;
ALTER TABLE notification ADD CONSTRAINT notification_action_check CHECK (action IN ('REVIEW', 'REWORK', 'ENDORSE',
    'SUBMIT_OVERDUE', 'REVIEW_OVERDUE', 'ENDORSE_OVERDUE', 'SYSTEM_MOVED', 'SYSTEM_TRANSFERRED_TO_YOU',
    'SYSTEM_TRANSFERRED_FROM_YOU', 'NOT_ENDORSED', 'VA_POC_REWORK', 'VA_POC_RETURNED', 'DECIDE_EXEMPTION',
    'EXEMPTION_APPROVED', 'EXEMPTION_REJECTED', 'WITHDRAWN', 'SUBMIT_DUE_SOON', 'SUBMIT_DUE_TODAY',
    'SUBMIT_STILL_OVERDUE', 'SUBMIT_OVERDUE_COPY', 'REVIEW_DUE_SOON', 'REVIEW_DUE_TODAY', 'REVIEW_STILL_OVERDUE',
    'REVIEW_OVERDUE_COPY', 'ENDORSE_DUE_IN_TWO_WEEKS', 'ENDORSE_DUE_IN_ONE_WEEK'));

-- The reminders' mail templates (MCNS onboarding, ticket 57): due soon, due today, overdue weekly and the copy.
ALTER TABLE mail_outbox DROP CONSTRAINT mail_outbox_template_check;
ALTER TABLE mail_outbox ADD CONSTRAINT mail_outbox_template_check CHECK (template IN ('RETURNED_FOR_REWORK', 'OVERDUE',
    'REGISTER_CHANGE', 'REMINDER_DUE_SOON', 'REMINDER_DUE', 'OVERDUE_WEEKLY', 'REMINDER_COPY'));
