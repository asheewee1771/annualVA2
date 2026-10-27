package sg.annualva.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Ticket 63, ADR 0058: the dev-local profile cannot start anywhere but a developer's machine. The guard is a bean, so a
 * refusal is a context that fails to start; start-up is not reachable over HTTP, so this runs the guard the way the
 * application does at startup, with the profiles, server address and database address a deployment would have.
 */
@ExtendWith(OutputCaptureExtension.class)
class DevLocalGuardTest {

    private static final String LOOPBACK = "server.address=127.0.0.1";

    private static JdbcConnectionDetails databaseAt(String url) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return "u";
            }

            @Override
            public String getPassword() {
                return "p";
            }

            @Override
            public String getJdbcUrl() {
                return url;
            }
        };
    }

    private ApplicationContextRunner startup(String database, String... profiles) {
        return new ApplicationContextRunner()
                .withPropertyValues("spring.profiles.active=" + String.join(",", profiles))
                .withBean(JdbcConnectionDetails.class, () -> databaseAt(database))
                .withBean(DevLocalGuard.class);
    }

    private ApplicationContextRunner local(String... profiles) {
        return startup("jdbc:postgresql://localhost:5432/annualva", profiles);
    }

    @Test
    void startsOnLoopbackWithOnlyTheDevLocalProfile() {
        for (String address : List.of("127.0.0.1", "localhost", "::1", "[::1]", "127.0.0.5")) {
            local("dev-local").withPropertyValues("server.address=" + address)
                    .run(context -> assertThat(context).as(address).hasNotFailed());
        }
    }

    @Test
    void refusesToStartWithTheSitProfile() {
        local("dev-local", "sit").withPropertyValues(LOOPBACK).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("dev-local").hasMessageContaining("sit");
        });
    }

    @Test
    void refusesToStartWithTheCiProfile() {
        local("ci", "dev-local").withPropertyValues(LOOPBACK)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void refusesToStartWithAnyOtherProfileSoAProductionNameCannotSlipThrough() {
        for (String other : List.of("prod", "production", "uat", "dev-mcc", "anything")) {
            local("dev-local", other).withPropertyValues(LOOPBACK)
                    .run(context -> assertThat(context).as(other).hasFailed());
        }
    }

    @Test
    void refusesToStartWhenTheServerAddressIsNotLoopback() {
        for (String address : List.of("0.0.0.0", "10.0.0.7", "192.168.1.20", "::", "example.com", "2001:db8::1", "")) {
            local("dev-local").withPropertyValues("server.address=" + address).run(context -> {
                assertThat(context).as("address '" + address + "'").hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("loopback");
            });
        }
    }

    @Test
    void refusesToStartWhenTheServerAddressIsNotSetBecauseThatListensOnEveryInterface() {
        local("dev-local").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("loopback");
        });
    }

    @Test
    void refusesToStartAgainstADatabaseThatIsNotOnThisMachine() {
        for (String url : List.of("jdbc:postgresql://db.internal:5432/annualva", "jdbc:postgresql://10.1.2.3/annualva",
                "not a url")) {
            startup(url, "dev-local").withPropertyValues(LOOPBACK).run(context -> {
                assertThat(context).as(url).hasFailed();
                assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("database");
            });
        }
    }

    @Test
    void startsAgainstADatabaseOnThisMachine() {
        for (String url : List.of("jdbc:postgresql://127.0.0.1:5432/annualva", "jdbc:postgresql://[::1]:5432/annualva",
                "jdbc:postgresql://localhost/annualva?sslmode=disable")) {
            startup(url, "dev-local").withPropertyValues(LOOPBACK)
                    .run(context -> assertThat(context).as(url).hasNotFailed());
        }
    }

    @Test
    void warnsThatAnyoneCanSignInAsAnyPersona(CapturedOutput output) {
        local("dev-local").withPropertyValues(LOOPBACK)
                .run(context -> assertThat(output.getAll()).contains("anyone who can reach this application"));
    }
}
