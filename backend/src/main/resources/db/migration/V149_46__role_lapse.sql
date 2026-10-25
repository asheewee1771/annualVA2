-- Role lapse and automatic return (ticket 46, story 80a, IM8 ac-3, ADR 0054). V4 already holds when a grant was last used
-- and when it lapsed; a lapsed grant that comes back by itself records when and why, so the VA POCs' role-holder review
-- (ticket 47) can list the automatic returns since its last review. Numbered 149.46 so it sorts after the migrations
-- already merged (V149, V149_42); it changes only role_grant, which V4 created.
--   returned_at     when the grant last came back after a lapse
--   return_trigger  why: REGISTER (an apply named the person again), QUARTER_START (the Quarter of the System began),
--                   RETURN_FOR_REWORK, SUBMITTED_FOR_REVIEW or EXEMPTION_LAPSE (work handed to them), or CONFIGURATION
--                   (a VA POC, at startup)
ALTER TABLE role_grant
    ADD COLUMN returned_at    timestamptz,
    ADD COLUMN return_trigger text,
    ADD CONSTRAINT role_grant_return_trigger_check CHECK (return_trigger IN ('REGISTER', 'QUARTER_START',
                                                          'RETURN_FOR_REWORK', 'SUBMITTED_FOR_REVIEW',
                                                          'EXEMPTION_LAPSE', 'CONFIGURATION')),
    ADD CONSTRAINT role_grant_returned_check CHECK ((returned_at IS NULL) = (return_trigger IS NULL)),
    -- Only configuration returns a VA POC, and configuration returns nobody else.
    ADD CONSTRAINT role_grant_va_poc_return_check CHECK ((role = 'VA_POC') = (return_trigger = 'CONFIGURATION'));
