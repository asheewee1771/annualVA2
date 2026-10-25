-- In-app notifications (ticket 36, stories 92 and 69, ADR 0043). A workflow move, an Overdue flag or a Register change
-- leaves one row per person who acts next, written after the move commits. A row carries only the System, the state,
-- the action, the due date and a link: never a Finding, a name of a vulnerability or anything a person typed, so a
-- notification can be forwarded (story 91). The System's ID and name are read from va_system when listed.
--
-- notification: one row per person per item; never deleted by the application.
--   recipient_id  the person it is for (app_user; a person the Register names who has not signed in yet is
--                 pre-registered by email, and the first sign-in links the same row)
--   system_id     the System it is about
--   submission_id the VA Submission it is about; null for a Not Started System or a Register change with none
--   state         the fixed state code it was sent in: NOT_STARTED or a VA Submission state
--   action        the fixed code of what the person is asked to do, or told; the screen's sentence is the app's own
--   due_on        the last day of the window that state waits on (V20 current_window_due), a business date; null when
--                 none applies
--   link          the app path that opens the item: fixed segments and ids only
--   read_at       when the person first marked it read (UTC); null while unread
--   group_key     the item both VA POCs receive as two rows (story 69); null for an item for one person
--   awaits        the VA POC decision that handles a pair (ENDORSEMENT: endorsing or returning the VA Submission); set
--                 exactly when group_key is
--   handled_at    when a VA POC made that decision (UTC); both rows of the pair carry it
--   handled_by    who made it, shown to the other VA POC as "handled by (name)"
CREATE TABLE notification (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id  uuid        NOT NULL REFERENCES app_user (id),
    system_id     uuid        NOT NULL REFERENCES va_system (id),
    submission_id uuid        REFERENCES va_submission (id),
    state         text        NOT NULL,
    action        text        NOT NULL,
    due_on        date,
    link          text        NOT NULL,
    created_at    timestamptz NOT NULL,
    read_at       timestamptz,
    group_key     uuid,
    awaits        text,
    handled_at    timestamptz,
    handled_by    uuid        REFERENCES app_user (id),
    CONSTRAINT notification_state_check CHECK (state IN ('NOT_STARTED', 'DRAFT', 'IN_REVIEW', 'RETURNED_FOR_REWORK',
        'AWAITING_ENDORSEMENT', 'ENDORSED', 'EXEMPTION_REQUESTED', 'EXEMPTED', 'WITHDRAWN', 'NOT_ENDORSED')),
    CONSTRAINT notification_action_check CHECK (action IN ('REVIEW', 'REWORK', 'ENDORSE', 'SUBMIT_OVERDUE',
        'REVIEW_OVERDUE', 'ENDORSE_OVERDUE', 'SYSTEM_MOVED', 'SYSTEM_TRANSFERRED_TO_YOU',
        'SYSTEM_TRANSFERRED_FROM_YOU')),
    CONSTRAINT notification_link_check CHECK (link ~ '^/[a-z0-9/-]{0,200}$'),
    CONSTRAINT notification_awaits_check CHECK (awaits IN ('ENDORSEMENT')),
    CONSTRAINT notification_pair_check CHECK ((group_key IS NULL) = (awaits IS NULL)),
    CONSTRAINT notification_handled_check CHECK ((handled_at IS NULL) = (handled_by IS NULL)
                                                 AND (handled_at IS NULL OR group_key IS NOT NULL))
);

-- A person's list, newest first.
CREATE INDEX notification_recipient_idx ON notification (recipient_id, created_at DESC);

-- The pairs a VA POC's decision on a VA Submission handles.
CREATE INDEX notification_open_pair_idx ON notification (submission_id, awaits) WHERE handled_at IS NULL AND group_key IS NOT NULL;
