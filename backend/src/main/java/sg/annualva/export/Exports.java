package sg.annualva.export;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sg.annualva.access.AuditActor;
import sg.annualva.access.CurrentUser;
import sg.annualva.access.StepUp;
import sg.annualva.audit.AuditAction;
import sg.annualva.audit.AuditEvent;
import sg.annualva.audit.AuditLog;
import sg.annualva.audit.AuditOutcome;
import sg.annualva.audit.DetailKey;
import sg.annualva.boundary.FileStore;
import sg.annualva.web.RejectedRequestException;
import sg.annualva.web.ResourceNotFoundException;

/**
 * Asking for an export, reading where it is, and downloading it (stories 81, 84 and 86). Asking is a step-up action (IM8
 * ac-2): without a sign-in from the last five minutes it is refused before anything is read. What the export will hold is
 * read then, with the caller's own access, and the export job makes the file ({@link ExportWorker}). An export is its
 * requester's alone: anyone else, another VA POC included, is told it does not exist. Asking and every download are
 * audited, with ids, fixed codes and numbers only.
 */
@Service
public class Exports {

    /** The step-up intent of asking for a management summary. */
    static final String MGMT_SUMMARY_INTENT = "export-mgmt-summary";

    /** How far back the list of a person's exports reaches: past the 24 hours a file is kept, so an expiry shows. */
    private static final Duration LISTED_FOR = Duration.ofHours(48);

    /** The most Systems one export may name. */
    private static final int MAX_SYSTEMS = 1000;

    /**
     * What a person asks for.
     *
     * @param type {@code MGMT_SUMMARY}
     * @param format {@code XLSX}
     * @param cycle the VA Cycle's year, or null for the one running today
     * @param quarter 1 to 4 for one Quarter, or null for the whole Cycle
     * @param systems the Systems' own ids to cover, or null for every System of the Quarter or Cycle
     */
    public record ExportRequest(
            @Nullable String type, @Nullable String format, @Nullable Integer cycle, @Nullable Integer quarter,
            @Nullable List<UUID> systems) {}

    /**
     * An export as its requester reads it.
     *
     * @param systemCount how many Systems it lists
     * @param expiresAt 24 hours after the file was made; null until it is
     */
    public record ExportView(
            UUID id, ExportType type, ExportFormat format, int cycle, @Nullable Integer quarter,
            Classification classification, int systemCount, ExportStatus status, Instant requestedAt,
            @Nullable Instant readyAt, @Nullable Instant expiresAt) {}

    /** A file to send as an attachment. */
    public record Download(String fileName, String mediaType, byte[] content) {}

    private final StepUp stepUp;
    private final ManagementSummaries summaries;
    private final ExportStore store;
    private final FileStore files;
    private final CurrentUser user;
    private final AuditLog audit;
    private final AuditActor actor;
    private final Clock clock;

    Exports(StepUp stepUp, ManagementSummaries summaries, ExportStore store, FileStore files, CurrentUser user,
            AuditLog audit, AuditActor actor, Clock clock) {
        this.stepUp = stepUp;
        this.summaries = summaries;
        this.store = store;
        this.files = files;
        this.user = user;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    /**
     * Queues an export. Checks, in order: a fresh sign-in ({@code 403 step-up-required}), the request's fields
     * ({@code 400 invalid-field}), and the VA Cycle ({@code 404} for one the app does not run).
     */
    @Transactional
    public ExportView request(ExportRequest request) {
        stepUp.require(MGMT_SUMMARY_INTENT);
        ExportType type = code(ExportType.class, request.type(), "type", "Choose MGMT_SUMMARY.");
        ExportFormat format = code(ExportFormat.class, request.format(), "format", "Choose XLSX.");
        Integer quarter = request.quarter();
        if (quarter != null && (quarter < 1 || quarter > 4)) {
            throw RejectedRequestException.invalidField("quarter", "Choose a Quarter from 1 to 4, or none for the Cycle.");
        }
        List<UUID> systems = request.systems();
        if (systems != null && (systems.isEmpty() || systems.size() > MAX_SYSTEMS || systems.contains(null))) {
            throw RejectedRequestException.invalidField("systems",
                    "Choose at least one System and at most 1000, or none for every System.");
        }
        ManagementSummary summary = summaries.read(request.cycle(), quarter, systems == null ? null : Set.copyOf(systems));
        UUID id = store.insert(user.id(), clock.instant(), type, format, summary);
        AuditEvent requested = actor.attribute(AuditEvent.of(AuditAction.EXPORT_REQUESTED, AuditOutcome.SUCCESS))
                .with(DetailKey.EXPORT_ID, id).with(DetailKey.EXPORT_TYPE, type.name())
                .with(DetailKey.FORMAT, format.name())
                .with(DetailKey.CLASSIFICATION, summary.classification().name())
                .with(DetailKey.CYCLE, summary.cycle()).with(DetailKey.COUNT, summary.systems().size());
        audit.record(quarter == null ? requested : requested.with(DetailKey.QUARTER, quarter));
        return view(owned(id));
    }

    /** The caller's exports asked for in the last 48 hours, newest first. */
    @Transactional(readOnly = true)
    public List<ExportView> mine() {
        return store.mine(user.id(), clock.instant().minus(LISTED_FOR)).stream().map(this::view).toList();
    }

    /** One of the caller's exports; anyone else's, or one that does not exist, is not found. */
    @Transactional(readOnly = true)
    public ExportView status(UUID id) {
        return view(owned(id));
    }

    /**
     * The file of one of the caller's exports, once it is made and until its 24 hours are up; the download is audited.
     *
     * @throws RejectedRequestException 409 {@code export-not-ready} while it is queued or after it failed, 410
     *     {@code export-expired} once it has expired; both are audited as refusals
     */
    @Transactional
    public Download download(UUID id) {
        ExportStore.Export export = owned(id);
        ExportStatus status = statusOf(export);
        if (status == ExportStatus.EXPIRED) {
            audit.recordIndependently(downloaded(AuditOutcome.REFUSED, id).with(DetailKey.REASON, "export-expired"));
            throw new RejectedRequestException(HttpStatus.GONE, "export-expired",
                    "This export has expired: an export is kept for 24 hours. Ask for it again.");
        }
        String key = export.fileKey();
        if (status != ExportStatus.READY || key == null) {
            audit.recordIndependently(downloaded(AuditOutcome.REFUSED, id).with(DetailKey.REASON, "export-not-ready"));
            throw new RejectedRequestException(HttpStatus.CONFLICT, "export-not-ready",
                    "This export cannot be downloaded: it is still being made, or making it failed.");
        }
        byte[] content = files.get(FileStore.Zone.EXPORT, key);
        audit.record(downloaded(AuditOutcome.SUCCESS, id).with(DetailKey.EXPORT_TYPE, export.type().name())
                .with(DetailKey.FORMAT, export.format().name())
                .with(DetailKey.CLASSIFICATION, export.classification().name()));
        return new Download(fileName(export), export.format().mediaType(), content);
    }

    private AuditEvent downloaded(AuditOutcome outcome, UUID id) {
        return actor.attribute(AuditEvent.of(AuditAction.EXPORT_DOWNLOADED, outcome)).with(DetailKey.EXPORT_ID, id);
    }

    private ExportStore.Export owned(UUID id) {
        return store.find(id).filter(export -> export.requestedBy().equals(user.id()))
                .orElseThrow(ResourceNotFoundException::new);
    }

    private ExportView view(ExportStore.Export export) {
        return new ExportView(export.id(), export.type(), export.format(), export.cycle(), export.quarter(),
                export.classification(), export.systemCount(), statusOf(export), export.requestedAt(), export.readyAt(),
                export.expiresAt());
    }

    /** A ready export whose 24 hours are up is expired, even before the export job removes its file. */
    private ExportStatus statusOf(ExportStore.Export export) {
        Instant expiresAt = export.expiresAt();
        boolean past = expiresAt != null && !clock.instant().isBefore(expiresAt);
        return export.status() == ExportStatus.READY && past ? ExportStatus.EXPIRED : export.status();
    }

    /** {@code management-summary-2027.xlsx}, or {@code management-summary-2027-q3.xlsx} for one Quarter. */
    private static String fileName(ExportStore.Export export) {
        return export.type().fileName() + "-" + export.cycle()
                + (export.quarter() == null ? "" : "-q" + export.quarter()) + "." + export.format().extension();
    }

    private static <E extends Enum<E>> E code(Class<E> type, @Nullable String raw, String field, String sentence) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(raw)) {
                return constant;
            }
        }
        throw RejectedRequestException.invalidField(field, sentence);
    }
}
