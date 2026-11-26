package sg.annualva.arch;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Ticket 68 as a gate: "a live report file" has one definition, the {@code report_file_live} view (V48, ADR 0061 item 8),
 * which leaves out the files the SMO/Tech Lead deleted. Every statement reads and writes report files through it, except
 * those listed here, each with why it must see deleted files too. It reads every store's static SQL as it is sent
 * ({@link StaticSql}), so a statement built from a listed fragment is a statement of its own and must be listed too. A
 * new statement that names the {@code report_file} table fails until it uses the view or is listed with its reason; and
 * {@code deleted_at} is written by the deletion and read by the sweep only, so no statement filters deleted files by hand.
 * The disposal's store is left out as a whole: its job is to remove every row of a Cycle, deleted files included (ADR
 * 0050, ADR 0061 item 2). The migrations are not read: a constraint or an index can name only the table.
 */
class LiveReportFileReadsTest {

    private static final String DISPOSAL_STORE = "sg.annualva.retention.DisposalStore";

    /** The table itself, not the view, not report_file_id or report_file_stage. */
    private static final Pattern TABLE = Pattern.compile("\\breport_file\\b", Pattern.CASE_INSENSITIVE);

    /** The table or its live view: the statements about report files. */
    private static final Pattern REPORT_FILES = Pattern.compile("\\breport_file(?:_live)?\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern DELETED_COLUMNS = Pattern.compile("\\bdeleted_(?:at|by)\\b", Pattern.CASE_INSENSITIVE);

    /** The rest of the "file a proposal names" reason, for every statement built from {@code REPLACED_FILE}. */
    private static final String NAMES_THE_REPLACED_FILE =
            "shows the name of the file a proposal names, which for FILE_DELETED is the deleted file (REPLACED_FILE)";

    /** The statements that name the table, and why each must see deleted files too (or touches no existing row). */
    private static final Map<String, String> SEE_DELETED_FILES = Map.ofEntries(
            Map.entry("ReportFileStore.INSERT", "creates a row, which is live"),
            Map.entry("ReportFileStore.HOLDS_CLEAN_COPY", "a deleted file keeps its content until disposal, so a pass "
                    + "that lost a race must not discard a copy a deleted row holds"),
            Map.entry("ReportFileStore.LEFTOVERS", "the sweep deletes the quarantined bytes of a deleted file too"),
            Map.entry("ReportFileStore.DEFER_RELEASE", "the sweep, of a deleted file too"),
            Map.entry("ReportFileStore.CLEAR_QUARANTINE_KEY", "the sweep, of a deleted file too"),
            Map.entry("FindingStore.REPLACED_FILE", NAMES_THE_REPLACED_FILE),
            Map.entry("FindingStore.FINDING_TABLES", "a Finding's current proposal " + NAMES_THE_REPLACED_FILE),
            Map.entry("FindingStore.PAGE", "the Findings page " + NAMES_THE_REPLACED_FILE),
            Map.entry("FindingStore.PROPOSALS", "a Finding's proposal history " + NAMES_THE_REPLACED_FILE),
            Map.entry("FindingStore.BATCHES", "a Bulk-set batch's proposal " + NAMES_THE_REPLACED_FILE),
            Map.entry("ReworkQueueStore.PAGE", "the rework queue " + NAMES_THE_REPLACED_FILE));

    /** The statements about report files that name deleted_at or deleted_by: the deletion writes them, the sweep reads them. */
    private static final Set<String> DELETED_COLUMN_USERS = Set.of("ReportFileStore.DELETE", "ReportFileStore.LEFTOVERS");

    private static List<StaticSql.Constant> statements;

    @BeforeAll
    static void readTheStores() throws IllegalAccessException {
        statements = StaticSql.of(new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("sg.annualva")).stream().filter(sql -> !sql.store().equals(DISPOSAL_STORE)).toList();
        assertThat(statements).as("the stores were read").isNotEmpty();
    }

    @Test
    void onlyTheListedStatementsNameTheTableRatherThanTheLiveView() {
        Set<String> naming = names(TABLE);

        assertThat(naming).as("statements that name report_file itself: read and write it through report_file_live, or "
                + "list the statement here with why it must see deleted files").isSubsetOf(SEE_DELETED_FILES.keySet());
        assertThat(new TreeSet<>(SEE_DELETED_FILES.keySet())).as("listed statements that no longer name the table")
                .isSubsetOf(naming);
    }

    @Test
    void deletedFilesAreFilteredByTheViewAloneNotByHand() {
        Set<String> naming = names(DELETED_COLUMNS);
        naming.retainAll(names(REPORT_FILES));

        assertThat(naming).as("statements about report files that name deleted_at or deleted_by: filter deleted files "
                + "through report_file_live instead").isEqualTo(new TreeSet<>(DELETED_COLUMN_USERS));
    }

    private static Set<String> names(Pattern pattern) {
        Set<String> names = new TreeSet<>();
        statements.stream().filter(sql -> pattern.matcher(sql.text()).find()).forEach(sql -> names.add(sql.name()));
        return names;
    }
}
