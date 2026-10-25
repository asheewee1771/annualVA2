package sg.annualva.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.Need;
import sg.annualva.cycle.CycleCalendar;
import sg.annualva.cycle.VaCycle;
import sg.annualva.submission.Exemptions;
import sg.annualva.submission.SubmissionState;
import sg.annualva.submission.SubmissionStep;
import sg.annualva.submission.Submissions;

/**
 * The VA POC's queue of Exemption requests waiting on a decision (story 45), in one VA Cycle, oldest first: the Systems
 * of the endorse scope whose VA Submission is Exemption Requested, read through the one dashboard reader
 * ({@link DashboardSystems}), each with its pending request and whether segregation of duties refuses the caller the
 * decision (the rule the decision itself is refused by). Beside them, the recorded VAs of conditional Exemptions waiting
 * on a confirmation (story 47), from every VA Cycle the app runs, since a Q4 one is confirmed after its Cycle has closed.
 * Both carry what an SMO/Tech Lead wrote, so the read is audited ({@link Exemptions#waiting}).
 */
@Service
public class ExemptionQueue {

    /**
     * One pending request.
     *
     * @param systemCode the System's business System ID
     * @param previousState the state a rejection restores
     * @param evidence what the SMO/Tech Lead wrote: data, shown only as text
     * @param sodConflict whether segregation of duties refuses the caller the decision; the other VA POC decides it
     */
    public record Request(
            UUID exemptionId, UUID submissionId, UUID systemId, String systemCode, String systemName, int quarter,
            Exemptions.Basis basis, LocalDate vaDate, String evidence, @Nullable UUID evidenceFileId,
            SubmissionState previousState, Instant requestedAt, boolean sodConflict) {}

    /**
     * One recorded VA of a conditional Exemption waiting on a confirmation (story 47).
     *
     * @param cycle the VA Cycle's year: a Q4 one is confirmed after its Cycle has closed
     * @param vaDate the day the VA was scheduled for
     * @param vaHappenedOn the day the SMO/Tech Lead says it happened
     * @param vaEvidence what they wrote about it: data, shown only as text
     * @param sodConflict whether segregation of duties refuses the caller the confirmation; the other VA POC confirms it
     */
    public record Confirmation(
            UUID exemptionId, UUID submissionId, UUID systemId, String systemCode, String systemName, int cycle,
            int quarter, LocalDate vaDate, LocalDate vaHappenedOn, String vaEvidence, Instant vaRecordedAt,
            boolean sodConflict) {}

    /**
     * @param cycle the VA Cycle's year of the {@code requests}
     * @param confirmations the recorded VAs to confirm, of every VA Cycle the app runs, the earliest recorded first
     */
    public record Queue(int cycle, List<Request> requests, List<Confirmation> confirmations) {}

    /** An Exempted VA Submission's dashboard row, with its VA Cycle. */
    private record Exempted(int cycle, DashboardSystems.Row row) {}

    private final CycleCalendar calendar;
    private final DashboardSystems systems;
    private final Exemptions exemptions;
    private final Submissions submissions;

    ExemptionQueue(CycleCalendar calendar, DashboardSystems systems, Exemptions exemptions, Submissions submissions) {
        this.calendar = calendar;
        this.systems = systems;
        this.exemptions = exemptions;
        this.submissions = submissions;
    }

    /**
     * @param requested the VA Cycle's year, or null for the one running today; a Cycle the app does not run is not found
     */
    @Transactional
    public Queue queue(@Nullable Integer requested) {
        VaCycle cycle = calendar.running(requested);
        Map<UUID, DashboardSystems.Row> frozen = systems.in(cycle, Need.ENDORSE).live().stream()
                .filter(row -> row.state() == SystemState.EXEMPTION_REQUESTED)
                .collect(Collectors.toMap(row -> Objects.requireNonNull(row.submissionId()), Function.identity()));
        Map<UUID, Exempted> exempted = new HashMap<>();
        for (VaCycle each : calendar.cycles()) {
            systems.in(each, Need.ENDORSE).live().stream().filter(row -> row.state() == SystemState.EXEMPTED)
                    .forEach(row -> exempted.put(Objects.requireNonNull(row.submissionId()), new Exempted(each.year(),
                            row)));
        }
        Set<UUID> conflicted = submissions.conflictedAt(
                Stream.concat(frozen.keySet().stream(), exempted.keySet().stream()).toList(), SubmissionStep.ENDORSE);
        Exemptions.Waiting waiting = exemptions.waiting(frozen.keySet(), exempted.keySet());
        List<Request> requests = waiting.pending().stream().map(pending -> {
            DashboardSystems.Row row = frozen.get(pending.submissionId());
            return new Request(pending.id(), pending.submissionId(), pending.systemId(), row.system().systemId(),
                    row.system().name(), row.system().quarter(), pending.basis(), pending.vaDate(), pending.evidence(),
                    pending.evidenceFileId(), pending.previousState(), pending.requestedAt(),
                    conflicted.contains(pending.submissionId()));
        }).toList();
        List<Confirmation> confirmations = waiting.toConfirm().stream().map(recorded -> {
            Exempted held = exempted.get(recorded.submissionId());
            DashboardSystems.Row row = held.row();
            return new Confirmation(recorded.id(), recorded.submissionId(), recorded.systemId(),
                    row.system().systemId(), row.system().name(), held.cycle(), row.system().quarter(),
                    recorded.vaDate(), Objects.requireNonNull(recorded.vaHappenedOn()),
                    Objects.requireNonNull(recorded.vaEvidence()), Objects.requireNonNull(recorded.vaRecordedAt()),
                    conflicted.contains(recorded.submissionId()));
        }).toList();
        return new Queue(cycle.year(), requests, confirmations);
    }
}
