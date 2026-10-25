package sg.annualva.export;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import sg.annualva.dashboard.SystemState;

/**
 * The words and the order of the management summary (story 81), whatever file it is made as: the title, the two parts
 * and their columns, and how a state and a flag are written. The Excel workbook and the PDF both write these, so the two
 * formats cannot disagree about what a column is called or where Endorsed Late goes.
 */
final class SummaryText {

    /** The part with the counts by Quarter and state. */
    static final String SUMMARY = "Summary";

    /** The part with one line per System. */
    static final String SYSTEMS = "Systems";

    /** The states counted, in the order of the spec's state table: every one but Withdrawn, which is never counted. */
    private static final List<SystemState> COUNTED =
            Arrays.stream(SystemState.values()).filter(state -> state != SystemState.WITHDRAWN).toList();

    /** The columns of the Systems part; the last, open Deviations, is a number. */
    static final List<String> SYSTEM_HEADERS = List.of("System ID", "System Name", "Quarter", "SMO/Tech Lead",
            "State", "Overdue", "Endorsed Late", "Open Deviations");

    /** How many columns of the Summary part come before the counts: the Quarter and its dates. */
    static final int QUARTER_TEXTS = 3;

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

    /** The columns of the Summary part: the Quarter, its dates, each counted state with Endorsed Late after Endorsed, and the total. */
    static List<String> quarterHeaders() {
        List<String> headers = new ArrayList<>(List.of("Quarter", "Starts", "Ends"));
        for (SystemState state : COUNTED) {
            headers.add(label(state));
            if (state == SystemState.ENDORSED) {
                headers.add("Endorsed Late");
            }
        }
        headers.add("Total");
        return headers;
    }

    /** The Quarter and its dates, the first {@link #QUARTER_TEXTS} columns of its line. */
    static List<String> quarterTexts(ManagementSummary.QuarterLine strip) {
        return List.of("Q" + strip.quarter(), strip.startsOn().toString(), strip.endsOn().toString());
    }

    /** The counts of its line, in the order of {@link #quarterHeaders()} after the dates. */
    static List<Long> quarterCounts(ManagementSummary.QuarterLine strip) {
        List<Long> counts = new ArrayList<>();
        for (SystemState state : COUNTED) {
            counts.add(strip.counts().getOrDefault(state, 0L));
            if (state == SystemState.ENDORSED) {
                counts.add(strip.endorsedLate());
            }
        }
        counts.add(strip.total());
        return counts;
    }

    /** Every column of a System's line but the last, open Deviations, which is {@link ManagementSummary.SystemLine#openDeviations()}. */
    static List<String> systemTexts(ManagementSummary.SystemLine line) {
        return List.of(line.systemId(), line.name(), "Q" + line.quarter(), line.smoEmail(), label(line.state()),
                yesNo(line.overdue()), yesNo(line.endorsedLate()));
    }

    private static String yesNo(boolean flag) {
        return flag ? "Yes" : "No";
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
