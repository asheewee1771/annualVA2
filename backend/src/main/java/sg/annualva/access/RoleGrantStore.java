package sg.annualva.access;

import static sg.annualva.jdbc.Timestamps.instant;
import static sg.annualva.jdbc.Timestamps.utc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The role_grant table. Constant SQL, named parameters (as-2). Roles change only through this
 * store, and only its callers write: configuration ({@link VaPocSync}), sign-in (attaching grants
 * made by email), a Register apply ({@link RegisterRoles}) and the role lapse, its returns and the suspensions
 * ({@link RoleLapse}); the role-holder review ({@link RoleHolders}) reads it. No controller may depend on it (an
 * architecture test holds that line).
 */
@Repository
public class RoleGrantStore {

    private static final String COLUMNS = "id, role, user_id, email, system_id, source, granted_at, granted_by, "
            + "expires_at, last_used_at, lapsed_at, suspended_at, suspension_cause, revoked_at, returned_at, return_trigger";

    private static final String ACTIVE_FOR_USER = "SELECT " + COLUMNS + " FROM role_grant WHERE user_id = :userId "
            + "AND revoked_at IS NULL AND lapsed_at IS NULL AND suspended_at IS NULL "
            + "AND (expires_at IS NULL OR expires_at > :now) ORDER BY granted_at, id";

    private static final String LIVE_BY_KEY = "SELECT " + COLUMNS + " FROM role_grant WHERE role = :role "
            + "AND email = CAST(:email AS citext) AND system_id IS NOT DISTINCT FROM CAST(:systemId AS uuid) "
            + "AND revoked_at IS NULL";

    /** The user is looked up by email so a grant for a person already known is attached at once. */
    private static final String INSERT = "INSERT INTO role_grant (role, user_id, email, system_id, source, "
            + "granted_at, granted_by, expires_at) VALUES (:role, "
            + "(SELECT id FROM app_user WHERE email = CAST(:email AS citext)), CAST(:email AS citext), "
            + ":systemId, :source, :now, :grantedBy, :expiresAt) ON CONFLICT DO NOTHING";

    private static final String ATTACH = "UPDATE role_grant SET user_id = :userId WHERE user_id IS NULL "
            + "AND email = CAST(:email AS citext) AND revoked_at IS NULL";

    private static final String MARK_USED = "UPDATE role_grant SET last_used_at = :now WHERE id IN (:ids)";

    private static final String LIVE_FROM_SOURCE = "SELECT " + COLUMNS + " FROM role_grant WHERE role = :role "
            + "AND source = :source AND revoked_at IS NULL";

    private static final String REVOKE = "UPDATE role_grant SET revoked_at = :now WHERE id = :id AND revoked_at IS NULL";

    /**
     * Unused since the cutoff (ADR 0055): a grant that still counts, whose last use, grant or return (GREATEST skips a
     * null) is before it. The job reads with it and then lapses with it, so a request that used the grant in between wins.
     * It is the store's half of the lapse rule; {@link RoleLapse#pass} adds the other, a Quarter of the System begun since.
     */
    private static final String UNUSED_SINCE = "revoked_at IS NULL AND lapsed_at IS NULL AND suspended_at IS NULL "
            + "AND (expires_at IS NULL OR expires_at > :now) AND GREATEST(granted_at, last_used_at, returned_at) < :cutoff";

    private static final String UNUSED = "SELECT " + COLUMNS + " FROM role_grant WHERE " + UNUSED_SINCE
            + " ORDER BY granted_at, id";

    private static final String LAPSE = "UPDATE role_grant SET lapsed_at = :now WHERE id = :id AND " + UNUSED_SINCE;

    private static final String LAPSED = "SELECT " + COLUMNS + " FROM role_grant WHERE role = :role AND source = :source "
            + "AND lapsed_at IS NOT NULL AND revoked_at IS NULL ORDER BY granted_at, id";

    private static final String LAPSED_ON = "SELECT " + COLUMNS + " FROM role_grant WHERE role = :role "
            + "AND source = 'REGISTER' AND system_id = :systemId AND lapsed_at IS NOT NULL AND revoked_at IS NULL "
            + "ORDER BY granted_at, id";

    /** The suspended Register grants, not revoked, of these Systems: the people a Register change may replace mid-flight. */
    private static final String SUSPENDED_ON = "SELECT " + COLUMNS + " FROM role_grant WHERE source = 'REGISTER' "
            + "AND system_id = ANY(CAST(:systems AS uuid[])) AND suspended_at IS NOT NULL AND revoked_at IS NULL "
            + "ORDER BY granted_at, id";

    /** A suspended grant does not come back by itself (ADR 0056): a VA POC restores it first. */
    private static final String RETURN = "UPDATE role_grant SET lapsed_at = NULL, returned_at = :now, "
            + "return_trigger = :trigger WHERE id = :id AND lapsed_at IS NOT NULL AND revoked_at IS NULL "
            + "AND suspended_at IS NULL";

    /** Every grant not revoked, with what the person's user record says (ticket 47): the role-holder review's list. */
    private static final String HOLDER_COLUMNS = "SELECT g.id, g.role, g.user_id, g.email, g.system_id, g.source, "
            + "g.granted_at, g.granted_by, g.expires_at, g.last_used_at, g.lapsed_at, g.suspended_at, g.suspension_cause, "
            + "g.revoked_at, g.returned_at, g.return_trigger, u.display_name, u.sso_id IS NOT NULL AS signed_in, "
            + "u.idp_disabled_at IS NOT NULL AS disabled "
            + "FROM role_grant g LEFT JOIN app_user u ON u.id = g.user_id WHERE g.revoked_at IS NULL";

    private static final String HOLDERS = HOLDER_COLUMNS + " ORDER BY g.granted_at, g.id";

    private static final String HOLDER = HOLDER_COLUMNS + " AND g.id = :id";

    private static final String NOT_SUSPENDED_OF_USER = "SELECT " + COLUMNS + " FROM role_grant WHERE user_id = :userId "
            + "AND revoked_at IS NULL AND suspended_at IS NULL ORDER BY granted_at, id";

    private static final String SUSPEND = "UPDATE role_grant SET suspended_at = :now, suspended_by = :by, "
            + "suspension_cause = :cause WHERE id = :id AND revoked_at IS NULL AND suspended_at IS NULL";

    /** Never for a person the identity provider reports disabled, even one disabled since the caller read the grant. */
    private static final String RESTORE ="UPDATE role_grant SET suspended_at = NULL, suspended_by = NULL, "
            + "suspension_cause = NULL WHERE id = :id AND revoked_at IS NULL AND suspended_at IS NOT NULL "
            + "AND NOT EXISTS (SELECT 1 FROM app_user u WHERE u.id = role_grant.user_id AND u.idp_disabled_at IS NOT NULL)";

    private final JdbcClient jdbc;

    RoleGrantStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Gives {@code email} the role, or returns the grant they already hold for the same role and System.
     * The grant is attached to the person's user record when they are already known; otherwise the
     * first sign-in attaches it.
     */
    public RoleGrant grant(Role role, String email, @Nullable UUID systemId, GrantSource source,
            @Nullable UUID grantedBy, @Nullable Instant expiresAt, Instant now) {
        jdbc.sql(INSERT)
                .param("role", role.name())
                .param("email", email)
                .param("systemId", systemId)
                .param("source", source.name())
                .param("now", utc(now))
                .param("grantedBy", grantedBy)
                .param("expiresAt", expiresAt == null ? null : utc(expiresAt))
                .update();
        return jdbc.sql(LIVE_BY_KEY)
                .param("role", role.name())
                .param("email", email)
                .param("systemId", systemId)
                .query(RoleGrantStore::map)
                .single();
    }

    /** The grant, not revoked, this email holds for this role and System, if any. */
    Optional<RoleGrant> liveByKey(Role role, String email, @Nullable UUID systemId) {
        return jdbc.sql(LIVE_BY_KEY).param("role", role.name()).param("email", email).param("systemId", systemId)
                .query(RoleGrantStore::map).optional();
    }

    /** The grants that count for this person at {@code now}: not lapsed, suspended, revoked or expired. */
    List<RoleGrant> activeFor(UUID userId, Instant now) {
        return jdbc.sql(ACTIVE_FOR_USER).param("userId", userId).param("now", utc(now)).query(RoleGrantStore::map).list();
    }

    /** Attaches the grants made by email, before the person had signed in, to their user record. */
    void attachPending(UUID userId, String email) {
        jdbc.sql(ATTACH).param("userId", userId).param("email", email).update();
    }

    /** Records that these grants were used at {@code now}. */
    void markUsed(Collection<UUID> ids, Instant now) {
        jdbc.sql(MARK_USED).param("ids", ids).param("now", utc(now)).update();
    }

    /** The live grants of a role that came from one source. */
    List<RoleGrant> live(Role role, GrantSource source) {
        return jdbc.sql(LIVE_FROM_SOURCE).param("role", role.name()).param("source", source.name())
                .query(RoleGrantStore::map).list();
    }

    void revoke(UUID id, Instant now) {
        jdbc.sql(REVOKE).param("id", id).param("now", utc(now)).update();
    }

    /** The grants that count at {@code now} and have not been used, granted or returned since {@code cutoff}. */
    List<RoleGrant> unusedSince(Instant cutoff, Instant now) {
        return jdbc.sql(UNUSED).param("cutoff", utc(cutoff)).param("now", utc(now)).query(RoleGrantStore::map).list();
    }

    /** Lapses the grant if it is still unused since {@code cutoff}; whether it did. */
    boolean lapse(UUID id, Instant cutoff, Instant now) {
        return jdbc.sql(LAPSE).param("id", id).param("cutoff", utc(cutoff)).param("now", utc(now)).update() == 1;
    }

    /** The lapsed grants, not revoked, of a role that came from one source. */
    List<RoleGrant> lapsed(Role role, GrantSource source) {
        return jdbc.sql(LAPSED).param("role", role.name()).param("source", source.name())
                .query(RoleGrantStore::map).list();
    }

    /** The lapsed Register grants, not revoked, of a role for one System. */
    List<RoleGrant> lapsedOn(Role role, UUID systemId) {
        return jdbc.sql(LAPSED_ON).param("role", role.name()).param("systemId", systemId)
                .query(RoleGrantStore::map).list();
    }

    /** Brings a lapsed grant back; whether it was lapsed. */
    boolean returned(UUID id, ReturnTrigger trigger, Instant now) {
        return jdbc.sql(RETURN).param("id", id).param("trigger", trigger.name()).param("now", utc(now)).update() == 1;
    }

    /** The suspended Register grants, not revoked, of these Systems. */
    List<RoleGrant> suspendedOn(Collection<UUID> systemIds) {
        if (systemIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SUSPENDED_ON).param("systems", systemIds.stream().map(UUID::toString).toArray(String[]::new))
                .query(RoleGrantStore::map).list();
    }

    /** Every grant not revoked, oldest first. */
    List<RoleHolder> holders() {
        return jdbc.sql(HOLDERS).query(RoleGrantStore::holder).list();
    }

    /** One grant not revoked, as the review lists it. */
    Optional<RoleHolder> holder(UUID id) {
        return jdbc.sql(HOLDER).param("id", id).query(RoleGrantStore::holder).optional();
    }

    /** A person's grants, not revoked, that are not suspended yet. */
    List<RoleGrant> notSuspendedOf(UUID userId) {
        return jdbc.sql(NOT_SUSPENDED_OF_USER).param("userId", userId).query(RoleGrantStore::map).list();
    }

    /** Suspends a grant; whether it was not suspended already. */
    boolean suspend(UUID id, SuspensionCause cause, @Nullable UUID by, Instant now) {
        return jdbc.sql(SUSPEND).param("id", id).param("cause", cause.name()).param("by", by).param("now", utc(now))
                .update() == 1;
    }

    /** Lifts a grant's suspension; whether it was suspended. */
    boolean restore(UUID id) {
        return jdbc.sql(RESTORE).param("id", id).update() == 1;
    }

    private static RoleHolder holder(ResultSet rs, int row) throws SQLException {
        return new RoleHolder(map(rs, row), rs.getString("display_name"), rs.getBoolean("signed_in"),
                rs.getBoolean("disabled"));
    }

    private static RoleGrant map(ResultSet rs, int row) throws SQLException {
        return new RoleGrant(
                rs.getObject("id", UUID.class),
                Role.valueOf(rs.getString("role")),
                rs.getObject("user_id", UUID.class),
                rs.getString("email"),
                rs.getObject("system_id", UUID.class),
                GrantSource.valueOf(rs.getString("source")),
                instant(rs, "granted_at"),
                rs.getObject("granted_by", UUID.class),
                instant(rs, "expires_at"),
                instant(rs, "last_used_at"),
                instant(rs, "lapsed_at"),
                instant(rs, "suspended_at"),
                named(rs, "suspension_cause", SuspensionCause.class),
                instant(rs, "revoked_at"),
                instant(rs, "returned_at"),
                named(rs, "return_trigger", ReturnTrigger.class));
    }

    private static <E extends Enum<E>> @Nullable E named(ResultSet rs, String column, Class<E> type) throws SQLException {
        String name = rs.getString(column);
        return name == null ? null : Enum.valueOf(type, name);
    }

}
