-- SMO Unreachable (ticket 39, story 13, ADR 0051): the mail relay refused the address of a System's SMO/Tech Lead, so the
-- System is flagged for the VA POCs to fix in the System Register.
--
-- va_system.smo_unreachable_at  when the relay first refused the SMO/Tech Lead's address (UTC) since a mail to it last
--                               went through; null while the address is not known to be refused. Set on every active
--                               System naming that address when a mail to it is settled REJECTED (V137), cleared on
--                               every System naming it when a later mail to it is SENT, and cleared on one System when
--                               a Register apply names another SMO/Tech Lead for it. Unlike Unlinked SMO (V6), it is
--                               stored: nothing else remembers what the relay said.
ALTER TABLE va_system ADD COLUMN smo_unreachable_at timestamptz;
