package sg.annualva.register;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The System Register, for the VA POCs only (the path is also restricted to them in the security chain, so a file
 * from anyone else is refused before it is read): the template to fill in, the upload of the filled-in file and the
 * preview of what it would change, and the apply of a confirmed preview (a step-up action, ADR 0018).
 */
@RestController
public class SystemRegisterController {

    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final RegisterTemplates templates;
    private final RegisterUploads uploads;
    private final RegisterApply apply;
    private final Clock clock;

    SystemRegisterController(RegisterTemplates templates, RegisterUploads uploads, RegisterApply apply, Clock clock) {
        this.templates = templates;
        this.uploads = uploads;
        this.apply = apply;
        this.clock = clock;
    }

    /**
     * A Register change preview as the caller sees it.
     *
     * @param canApply false when any row is BLOCKED, or the preview has been applied or has expired
     * @param expiresAt one hour after the upload
     * @param rows in the order they are shown; each has exactly one outcome
     */
    public record PreviewView(
            UUID id, String status, Instant createdAt, Instant expiresAt, boolean canApply, Preview.Counts counts,
            List<PreviewRow> rows) {}

    /** The fixed Excel template (story 1) as an attachment; the download is audited. */
    @GetMapping("/api/system-register/template")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<byte[]> template() {
        RegisterTemplates.Template template = templates.download();
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(template.fileName()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(template.content());
    }

    /**
     * Uploads a filled-in template (story 2): up to 2 MB, its type decided from its bytes. Answers with the preview
     * (201, with its address); nothing in the Register changes. A file that is not a Register workbook is a problem
     * with a fixed type and sentence that never repeats the file.
     */
    @PostMapping(path = "/api/system-register/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<PreviewView> upload(@RequestParam("file") MultipartFile file) {
        Preview preview = uploads.upload(file.getSize(), file);
        return ResponseEntity.created(URI.create("/api/system-register/previews/" + preview.id()))
                .cacheControl(CacheControl.noStore())
                .body(view(preview));
    }

    /** A preview read back, until it expires after an hour (410) or when it never existed (404); the read is audited. */
    @GetMapping("/api/system-register/previews/{id}")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<PreviewView> preview(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view(uploads.preview(id)));
    }

    /**
     * What an apply did: the preview, when, how many Systems were added, updated, retired and left as they were, and how
     * many role grants were given and revoked.
     */
    public record ApplyView(
            UUID previewId, String status, Instant appliedAt, int added, int updated, int retired, int unchanged,
            int rolesGranted, int rolesRevoked, int withdrawn) {}

    /**
     * What the VA POC confirms with an apply.
     *
     * @param previewId the preview, named only so the front end can reopen it after a fresh sign-in; the address names
     *     the preview applied
     * @param withdraw the VA Submissions they confirmed withdrawing, one by one (story 6); none when null
     */
    public record ApplyRequest(@Nullable UUID previewId, @Nullable List<UUID> withdraw) {}

    /**
     * Applies a confirmed preview (stories 2 to 4, 6, 73, 79): the step-up action for the Register, so it is refused with
     * {@code step-up-required} unless the caller signed in again within five minutes. Answers 409 for a preview that
     * has been applied, has expired, has a blocked row or was made against a Register, or in-flight work, that has since
     * changed, and 400 {@code withdrawal-unconfirmed} unless {@code withdraw} names exactly the VA Submissions it withdraws.
     */
    @PostMapping("/api/system-register/previews/{id}/apply")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<ApplyView> apply(@PathVariable UUID id, @RequestBody(required = false) @Nullable ApplyRequest body) {
        List<UUID> withdraw = body == null || body.withdraw() == null ? List.of() : body.withdraw();
        RegisterApply.Applied applied = apply.apply(id, withdraw);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ApplyView(applied.previewId(), "APPLIED",
                applied.appliedAt(), applied.added(), applied.updated(), applied.retired(), applied.unchanged(),
                applied.rolesGranted(), applied.rolesRevoked(), applied.withdrawn()));
    }

    private PreviewView view(Preview preview) {
        return new PreviewView(preview.id(), preview.status(), preview.createdAt(), preview.expiresAt(),
                preview.canApply(clock.instant()), preview.counts(), preview.rows());
    }
}
