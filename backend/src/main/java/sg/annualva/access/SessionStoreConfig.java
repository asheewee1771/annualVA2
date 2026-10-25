package sg.annualva.access;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/** The session-store collaborators shared by the sign-in handlers and the filter chain wiring. */
@Configuration(proxyBeanMethods = false)
class SessionStoreConfig {

    /**
     * Spring Session's own query for a person's sessions, ordered by session (ADR 0057). Its reader builds one session
     * from each run of adjacent rows, and the query as shipped has no order: once the store holds many rows of one person,
     * PostgreSQL joins by hash, a session's attribute rows come back apart, and the reader keeps a part of it. The one-session
     * rule then misses the "ended" mark on an older session, treats it as live, and the person's next step-up ends the
     * session it is made in.
     */
    static final String SESSIONS_OF_A_PERSON = """
            SELECT S.PRIMARY_ID, S.SESSION_ID, S.CREATION_TIME, S.LAST_ACCESS_TIME, S.MAX_INACTIVE_INTERVAL,
                SA.ATTRIBUTE_NAME, SA.ATTRIBUTE_BYTES
            FROM %TABLE_NAME% S
            LEFT JOIN %TABLE_NAME%_ATTRIBUTES SA ON S.PRIMARY_ID = SA.SESSION_PRIMARY_ID
            WHERE S.PRINCIPAL_NAME = ?
            ORDER BY S.SESSION_ID
            """;

    /** Explicit so the success handler can save the swapped context through the same repository the chain reads. */
    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /** The one-session rule reads the session store, so it holds across restarts and nodes. */
    @Bean
    <S extends Session> SessionRegistry sessionRegistry(FindByIndexNameSessionRepository<S> sessions) {
        return new SpringSessionBackedSessionRegistry<>(sessions);
    }

    /** Reads a person's sessions whole, each one's rows together ({@link #SESSIONS_OF_A_PERSON}). */
    @Bean
    SessionRepositoryCustomizer<JdbcIndexedSessionRepository> sessionsOfAPersonInOrder() {
        return repository -> repository.setListSessionsByPrincipalNameQuery(SESSIONS_OF_A_PERSON);
    }
}
