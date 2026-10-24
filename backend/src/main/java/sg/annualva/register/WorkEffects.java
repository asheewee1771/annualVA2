package sg.annualva.register;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What each row of a Register change preview does to in-flight work (stories 5 to 7, ADR 0048), the one place the rule
 * is written: the upload classifies its rows with it, and the apply classifies the stored rows again with what it holds,
 * so it applies exactly what was shown. A pure function of the rows and of the in-flight VA Submissions of their Systems.
 *
 * <ul>
 *   <li>A RETIRE of a System with in-flight work is Will withdraw. It is applied once the VA POC confirms each VA
 *       Submission (story 6), and Blocked while any of their files is still being checked (decision 2026-10-18).
 *   <li>An UPDATE that moves the System to another Quarter is Will move when it has in-flight work in a VA Cycle that
 *       follows the Register (an older Cycle keeps its Quarters, ADR 0044); one that changes its SMO/Tech Lead or Cyber
 *       Manager is Will transfer when it has in-flight work in any Cycle. Both are Blocked in Release 1, until the
 *       mid-cycle tickets of Release 2.
 *   <li>A transfer is also Blocked when the new SMO/Tech Lead or Cyber Manager acted at, or was assigned to, another step
 *       of an in-flight VA Submission (story 58): Release 2 lifts the transfer block, not this one.
 * </ul>
 *
 * A row that touches no in-flight work, an ADD, a NO_CHANGE and a row Blocked by the file itself have no effect.
 */
final class WorkEffects {

    private WorkEffects() {}

    /** The Systems whose in-flight work the rows could touch: those a RETIRE or an UPDATE names. */
    static Set<UUID> systemsOf(List<PreviewRow> rows) {
        Set<UUID> systems = new LinkedHashSet<>();
        for (PreviewRow row : rows) {
            if ((row.outcome() == Outcome.RETIRE || row.outcome() == Outcome.UPDATE) && row.existingSystemId() != null) {
                systems.add(row.existingSystemId());
            }
        }
        return systems;
    }

    /**
     * The rows with their effects on the in-flight work given, each row's earlier effects replaced.
     *
     * @param work the in-flight VA Submissions of the rows' Systems, by System
     * @param olderCycles the VA Cycles that keep their Systems as they were when a newer one opened, so a move changes
     *     nothing in them
     */
    static List<PreviewRow> classify(List<PreviewRow> rows, Map<UUID, List<InFlightWork.Work>> work,
            Set<Integer> olderCycles) {
        return rows.stream().map(row -> classify(row, work, olderCycles)).toList();
    }

    private static PreviewRow classify(PreviewRow row, Map<UUID, List<InFlightWork.Work>> work,
            Set<Integer> olderCycles) {
        List<InFlightWork.Work> inFlight = row.existingSystemId() == null
                ? List.of() : work.getOrDefault(row.existingSystemId(), List.of());
        List<Effect> effects = new ArrayList<>();
        List<BlockReason> reasons = new ArrayList<>();
        if (!inFlight.isEmpty() && row.outcome() == Outcome.RETIRE) {
            effects.add(Effect.WILL_WITHDRAW);
            if (inFlight.stream().anyMatch(InFlightWork.Work::filesScanning)) {
                reasons.add(BlockReason.FILES_SCANNING);
            }
        } else if (!inFlight.isEmpty() && row.outcome() == Outcome.UPDATE) {
            SystemSnapshot before = Objects.requireNonNull(row.before(), "an UPDATE has a before");
            SystemSnapshot after = Objects.requireNonNull(row.after(), "an UPDATE has an after");
            if (!before.quarter().equals(after.quarter())
                    && inFlight.stream().anyMatch(item -> !olderCycles.contains(item.cycle()))) {
                effects.add(Effect.WILL_MOVE);
                reasons.add(BlockReason.MOVE_IN_FLIGHT);
            }
            if (!before.smoEmail().equals(after.smoEmail())
                    || !before.cyberManagerEmail().equals(after.cyberManagerEmail())) {
                effects.add(Effect.WILL_TRANSFER);
                reasons.add(BlockReason.TRANSFER_IN_FLIGHT);
                if (inFlight.stream().anyMatch(item -> twoSteps(before, after, item))) {
                    reasons.add(BlockReason.SEGREGATION_OF_DUTIES);
                }
            }
        }
        if (effects.isEmpty()) {
            return new PreviewRow(row.sheetRow(), row.systemId(), row.existingSystemId(), row.outcome(), row.reasons(),
                    row.unlinkedSmo(), row.before(), row.after());
        }
        return new PreviewRow(row.sheetRow(), row.systemId(), row.existingSystemId(),
                reasons.isEmpty() ? row.outcome() : Outcome.BLOCKED, reasons, row.unlinkedSmo(), row.before(),
                row.after(), effects, inFlight.stream()
                        .map(item -> new PreviewRow.InFlight(item.submissionId(), item.cycle(), item.state())).toList());
    }

    /**
     * Whether the change gives one person two steps of the VA Submission (story 58): the new SMO/Tech Lead reviewed or
     * endorsed it, or was its Cyber Manager; or the new Cyber Manager started, submitted or endorsed it, or was its SMO/Tech
     * Lead. Emails are lower-case on both sides.
     */
    private static boolean twoSteps(SystemSnapshot before, SystemSnapshot after, InFlightWork.Work work) {
        String smo = after.smoEmail();
        String manager = after.cyberManagerEmail();
        boolean smoElsewhere = smo.equals(before.cyberManagerEmail()) || work.reviewers().contains(smo)
                || work.endorsers().contains(smo);
        boolean managerElsewhere = manager.equals(before.smoEmail()) || work.submitters().contains(manager)
                || work.endorsers().contains(manager);
        return smoElsewhere || managerElsewhere;
    }

    /** The VA Submissions the rows would withdraw, each of which the VA POC confirms (story 6). */
    static Set<UUID> withdrawals(List<PreviewRow> rows) {
        Set<UUID> withdrawn = new LinkedHashSet<>();
        rows.stream().filter(row -> row.effects().contains(Effect.WILL_WITHDRAW))
                .forEach(row -> row.inFlight().forEach(item -> withdrawn.add(item.submissionId())));
        return withdrawn;
    }

    /**
     * Whether two classifications of the same rows say the same about in-flight work: the same outcome, reasons and
     * effects on the same VA Submissions. A state that moved on in between (a Draft submitted, say) is not a difference:
     * what the VA POC confirmed is the VA Submission.
     */
    static boolean sameEffects(List<PreviewRow> shown, List<PreviewRow> now) {
        return shown.stream().map(WorkEffects::effectsOf).toList().equals(now.stream().map(WorkEffects::effectsOf).toList());
    }

    private static List<Object> effectsOf(PreviewRow row) {
        return List.of(row.outcome(), row.reasons(), row.effects(),
                row.inFlight().stream().map(PreviewRow.InFlight::submissionId).toList());
    }
}
