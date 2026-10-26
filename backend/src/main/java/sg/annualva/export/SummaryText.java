package sg.annualva.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import sg.annualva.dashboard.SystemState;
import sg.annualva.export.ExportTable.Cell;
import sg.annualva.export.ExportTable.Column;

/**
 * What the management summary says (story 81), whatever file it is made as: its title, the date it was read on, and its
 * two tables, Summary (counts by Quarter and state with Endorsed Late and Exemption Lapsed) and Systems (one row per
 * System), with their columns in order and how a state and a flag are written. The Excel workbook and the PDF both write these tables, so
 * the two formats cannot disagree about a column, its place or its value.
 */
final class SummaryText {

    /** The states counted, in the order of the spec's state table: every one but Withdrawn, which is never counted. */
    private static final List<SystemState> COUNTED =
            Arrays.stream(SystemState.values()).filter(state -> state != SystemState.WITHDRAWN).toList();

    private static final double COUNT_WIDTH = 14;

    /** One column of the Summary table and how a Quarter's line fills it. */
    private record QuarterColumn(Column column, Function<ManagementSummary.QuarterLine, Cell> cell) {}

    /** One column of the Systems table and how a System's line fills it. */
    private record SystemColumn(Column column, Function<ManagementSummary.SystemLine, Cell> cell) {}

    /** The Quarter, its dates, each counted state with Endorsed Late after Endorsed, and the total. */
    private static final List<QuarterColumn> QUARTER_COLUMNS = quarterColumns();

    private static final List<SystemColumn> SYSTEM_COLUMNS = List.of(
            new SystemColumn(new Column("System ID", 16), line -> text(line.systemId())),
            new SystemColumn(new Column("System Name", 36), line -> text(line.name())),
            new SystemColumn(new Column("Quarter", 9), line -> text("Q" + line.quarter())),
            new SystemColumn(new Column("SMO/Tech Lead", 32), line -> text(line.smoEmail())),
            new SystemColumn(new Column("State", 22), line -> text(label(line.state()))),
            new SystemColumn(new Column("Overdue", 10), line -> yesNo(line.overdue())),
            new SystemColumn(new Column("Endorsed Late", 14), line -> yesNo(line.endorsedLate())),
            new SystemColumn(new Column("Exemption Lapsed", 17), line -> yesNo(line.exemptionLapsed())),
            new SystemColumn(new Column("Open Deviations", 16), line -> new Cell.Count(line.openDeviations())));

    private SummaryText() {}

    /** "Management summary, VA Cycle 2027", with ", Q3" when it covers one Quarter. */
    static String title(ManagementSummary summary) {
        return "Management summary, VA Cycle " + summary.cycle()
                + (summary.quarter() == null ? "" : ", Q" + summary.quarter());
    }

    /** "As at 2027-08-16": the business date it was read on. */
    static String asAt(ManagementSummary summary) {
        return "As at " + summary.asAt();
    }

    /** The Summary table: one row per Quarter it counts. */
    static ExportTable quarters(ManagementSummary summary) {
        return new ExportTable("Summary", QUARTER_COLUMNS.stream().map(QuarterColumn::column).toList(),
                summary.quarters().stream()
                        .map(line -> QUARTER_COLUMNS.stream().map(column -> column.cell().apply(line)).toList())
                        .toList());
    }

    /** The Systems table: one row per System. */
    static ExportTable systems(ManagementSummary summary) {
        return new ExportTable("Systems", SYSTEM_COLUMNS.stream().map(SystemColumn::column).toList(),
                summary.systems().stream()
                        .map(line -> SYSTEM_COLUMNS.stream().map(column -> column.cell().apply(line)).toList())
                        .toList());
    }

    private static List<QuarterColumn> quarterColumns() {
        List<QuarterColumn> columns = new ArrayList<>(List.of(
                new QuarterColumn(new Column("Quarter", 9), line -> text("Q" + line.quarter())),
                new QuarterColumn(new Column("Starts", 11.5), line -> text(line.startsOn().toString())),
                new QuarterColumn(new Column("Ends", 11.5), line -> text(line.endsOn().toString()))));
        for (SystemState state : COUNTED) {
            columns.add(new QuarterColumn(new Column(label(state), COUNT_WIDTH),
                    line -> new Cell.Count(line.counts().getOrDefault(state, 0L))));
            if (state == SystemState.ENDORSED) {
                columns.add(new QuarterColumn(new Column("Endorsed Late", COUNT_WIDTH),
                        line -> new Cell.Count(line.endorsedLate())));
            }
        }
        columns.add(new QuarterColumn(new Column("Exemption Lapsed", 17),
                line -> new Cell.Count(line.exemptionLapsed())));
        columns.add(new QuarterColumn(new Column("Total", COUNT_WIDTH), line -> new Cell.Count(line.total())));
        return List.copyOf(columns);
    }

    private static Cell text(String value) {
        return new Cell.Text(value);
    }

    private static Cell yesNo(boolean flag) {
        return text(flag ? "Yes" : "No");
    }

    /** A state as the glossary names it. */
    static String label(SystemState state) {
        return switch (state) {
            case NOT_STARTED -> "Not Started";
            case DRAFT -> "Draft";
            case IN_REVIEW -> "In Review";
            case RETURNED_FOR_REWORK -> "Returned for Rework";
            case AWAITING_ENDORSEMENT -> "Awaiting Endorsement";
            case ENDORSED -> "Endorsed";
            case EXEMPTION_REQUESTED -> "Exemption Requested";
            case EXEMPTED -> "Exempted";
            case WITHDRAWN -> "Withdrawn";
            case NOT_ENDORSED -> "Not Endorsed";
        };
    }
}
