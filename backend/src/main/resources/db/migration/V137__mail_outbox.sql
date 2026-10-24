-- The mail outbox (ticket 37, stories 91 and 94, ADR 0045). A required notification (V136) is queued for email in the
-- transaction that writes it; the dispatcher hands it to the mail relay later, retrying a failed hand-over with backoff
-- up to five attempts. A row holds no address and no value of the message: the recipient, the System, the state, the
-- action, the due date and the link are the notification's own, read when the mail is sent. Tickets 38 (reminders) and
-- 39 (bounces) build on it.
--
-- mail_outbox: one row per notification mailed; never deleted by the application.
--   notification_id   the notification it mails, at most once
--   template          the logical template the mail is sent with; the relay's id for it is configuration
--   status            PENDING until it is settled: SENT (the relay took it), REJECTED (the relay refused the address),
--                     REFUSED (a value fell outside the allow-list, so the relay was never called) or FAILED (every
--                     attempt failed)
--   attempts          how many times the dispatcher has claimed it
--   next_attempt_at   when it may be claimed next (UTC): the backoff after a failure, or the lease of a claim
--   last_attempt_at   when it was last claimed (UTC)
--   sent_at           when the relay took it (UTC)
--   relay_message_id  the relay's id of the message, for matching a later receipt (ticket 39)
CREATE TABLE mail_outbox (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    notification_id  uuid        NOT NULL REFERENCES notification (id),
    template         text        NOT NULL,
    status           text        NOT NULL DEFAULT 'PENDING',
    attempts         integer     NOT NULL DEFAULT 0,
    created_at       timestamptz NOT NULL,
    next_attempt_at  timestamptz NOT NULL,
    last_attempt_at  timestamptz,
    sent_at          timestamptz,
    relay_message_id text,
    CONSTRAINT mail_outbox_notification_key UNIQUE (notification_id),
    CONSTRAINT mail_outbox_template_check CHECK (template IN ('RETURNED_FOR_REWORK', 'OVERDUE', 'REGISTER_CHANGE')),
    CONSTRAINT mail_outbox_status_check CHECK (status IN ('PENDING', 'SENT', 'REJECTED', 'REFUSED', 'FAILED')),
    CONSTRAINT mail_outbox_attempts_check CHECK (attempts >= 0),
    CONSTRAINT mail_outbox_sent_check CHECK ((status = 'SENT') = (sent_at IS NOT NULL)
                                             AND (status = 'SENT') = (relay_message_id IS NOT NULL))
);

-- What the dispatcher claims next.
CREATE INDEX mail_outbox_due_idx ON mail_outbox (next_attempt_at) WHERE status = 'PENDING';
