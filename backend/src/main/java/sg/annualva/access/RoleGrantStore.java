package sg.annualva.access;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The role_grant table. Constant SQL, named parameters (as-2). Roles change only through this
 * store, and only its callers write: configuration ({@link VaPocSync}), sign-in (attaching grants
 * made by email), a Register apply ({@link RegisterRoles}), the role lapse and its returns ({@link RoleLapse}) and, in a
 * later slice, the role-holder review. No controller
 * may depend on it (an architecture test holds that line).
 */
@Repository
public class RoleGrantStore {

    private static final String COLUMNS = "id, role, user_id, email, system_id, source, granted_at, granted_by, "
            + "expires_at, last_used_at, lapsed_at, suspended_at, revoked_at";

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
     * The one lapse rule (ADR 0053): a grant that still counts, whose last use, grant or return (GREATEST skips a null) is
     * before the cutoff. The job reads with it and then lapses with it, so a request that used the grant in between wins.
     */
    private static final String UNUSED_SINCE = "revoked_at IS NULL AND lapsed_at IS NULL AND suspended_at IS NULL "
            + "AND (expires_at IS NULL OR expires_at > :now) AND GREATEST(granted_at, last_used_at, returned_at) < :cutoff";

    private static final String UNUSED = "SELECT " + COLUMNS + " FROM role_grant WHERE " + UNUSED_SINCE
            + " ORDER BY granted_at, id";

    private static final String LAPSE = "UPDATE role_grant SET lapsed_at = :now WHERE id = :id AND " + UNUSED_SINCE;

    private static final String LAPSED = "SELECT " + COLUMNS + " FROM role_grant WHERE role = :role AND source = :source "
            + "AND lapsed_at IS NOT NULL AND revoked_at IS NULL ORDER BY granted_at, id";

    private static final String RETURN = "UPDATE role_grant SET lapsed_at = NULL, returned_at = :now, "
            + "return_trigger = :trigger WHERE id = :id AND lapsed_at IS NOT NULL AND revoked_at IS NULL";

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

    /** Brings a lapsed grant back; whether it was lapsed. */
    boolean returned(UUID id, ReturnTrigger trigger, Instant now) {
        return jdbc.sql(RETURN).param("id", id).param("trigger", trigger.name()).param("now", utc(now)).update() == 1;
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
                instant(rs, "revoked_at"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
