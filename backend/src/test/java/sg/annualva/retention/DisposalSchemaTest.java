package sg.annualva.retention;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import sg.annualva.harness.Seam1Test;

/**
 * ADR 0048 as a gate on the schema the migrations build: the disposal deletes rows from many modules' tables in
 * foreign-key order, so a later table that names one of them, and is not disposed of first, would make every approved
 * list fail at execution. This reads every foreign key from the database and fails until the new table is added to
 * {@link DisposalStore#STEPS} in its place.
 */
class DisposalSchemaTest extends Seam1Test {

    private static final Pattern DELETE = Pattern.compile("^DELETE FROM (\\w+) ");
    private static final Pattern CLEAR = Pattern.compile("^UPDATE (\\w+) SET ");

    private static final String FOREIGN_KEYS = "SELECT child.relname AS child, parent.relname AS parent "
            + "FROM pg_constraint c JOIN pg_class child ON child.oid = c.conrelid "
            + "JOIN pg_class parent ON parent.oid = c.confrelid "
            + "WHERE c.contype = 'f' AND c.connamespace = to_regnamespace(current_schema())";

    private record ForeignKey(String child, String parent) {}

    @Autowired
    private JdbcClient database;

    @Test
    void everyTableThatNamesADisposedTableIsDisposedOfBeforeIt() {
        List<String> deleted = new ArrayList<>();
        List<String> cleared = new ArrayList<>();
        for (DisposalStore.Step step : DisposalStore.STEPS) {
            Matcher delete = DELETE.matcher(step.sql());
            Matcher clear = CLEAR.matcher(step.sql());
            if (delete.find()) {
                deleted.add(delete.group(1));
                cleared.add(null);
            } else if (clear.find()) {
                deleted.add(null);
                cleared.add(clear.group(1));
            }
        }
        List<ForeignKey> keys = database.sql(FOREIGN_KEYS)
                .query((rs, row) -> new ForeignKey(rs.getString("child"), rs.getString("parent"))).list();
        assertThat(keys).as("the schema has foreign keys to check").isNotEmpty();

        List<String> misses = new ArrayList<>();
        for (ForeignKey key : keys) {
            int parentAt = deleted.indexOf(key.parent());
            if (parentAt < 0 || key.child().equals(key.parent())) {
                continue;
            }
            int childAt = deleted.indexOf(key.child());
            int clearedAt = cleared.indexOf(key.child());
            boolean before = (childAt >= 0 && childAt < parentAt) || (clearedAt >= 0 && clearedAt < parentAt);
            if (!before) {
                misses.add(key.child() + " names " + key.parent());
            }
        }
        assertThat(misses).as("tables that name a disposed table but are not disposed of, or cleared, before it")
                .isEmpty();
    }
}
