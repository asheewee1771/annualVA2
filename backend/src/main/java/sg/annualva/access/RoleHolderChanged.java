package sg.annualva.access;

import java.time.Instant;
import java.util.UUID;

/**
 * A VA POC gave a System someone who can act at a step again (decided 2026-10-26): they restored its SMO/Tech Lead's or
 * Cyber Manager's suspended role, or applied a Register that revoked the role or brought a lapsed one back. Published
 * inside the command's transaction; the notifications listen after the commit (ADR 0043) and clear the pair that told
 * both VA POCs nobody could act. It carries ids and a role only.
 *
 * @param role the Register role for the System that changed hands or came back: SMO/Tech Lead or Cyber Manager
 * @param by the VA POC whose command it was
 * @param at when (the clock bean's instant)
 */
public record RoleHolderChanged(UUID systemId, Role role, UUID by, Instant at) {}
