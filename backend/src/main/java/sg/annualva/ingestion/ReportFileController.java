package sg.annualva.ingestion;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import sg.annualva.finding.ReportType;
import sg.annualva.web.RejectedRequestException;

/**
 * The Scan Report files of a VA Submission. The SMO/Tech Lead of the System uploads (a Cyber Manager, a VA POC who is not
 * its SMO or an Auditor is refused with 403, an SMO of another System gets 404, ADR 0013); anyone who may see the System
 * reads a file's status and, once it is clean, downloads it as an attachment.
 */
@RestController
public class ReportFileController {

    private final ReportFiles files;

    ReportFileController(ReportFiles files) {
        this.files = files;
    }

    /**
     * Uploads one file of a Scan Report (story 20): multipart with the report type, the kind and the {@code file} part.
     * The file is quarantined and answered with 202 and its status, QUARANTINED; the scan job takes it from there. Its
     * type is decided from its bytes, never from the declared name or content type.
     */
    @PostMapping(path = "/api/submissions/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('SMO')")
    public ResponseEntity<ReportFileView> upload(@PathVariable UUID id, @RequestParam ReportType reportType,
            @RequestParam FileKind kind, @RequestParam("file") MultipartFile file) {
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            throw new RejectedRequestException(HttpStatus.BAD_REQUEST, "file-unreadable", "The file could not be read.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        ReportFileView uploaded = files.upload(id, reportType, kind, name, content);
        return ResponseEntity.accepted()
                .location(URI.create("/api/submissions/" + id + "/files/" + uploaded.id()))
                .cacheControl(CacheControl.noStore())
                .body(uploaded);
    }

    /** The VA Submission's files with their pipeline status, oldest first. */
    @GetMapping("/api/submissions/{id}/files")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<List<ReportFileView>> list(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.list(id));
    }

    /** One file's pipeline status and, when it was rejected, the reject code and the plain message. */
    @GetMapping("/api/submissions/{id}/files/{fileId}")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<ReportFileView> status(@PathVariable UUID id, @PathVariable UUID fileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.status(id, fileId));
    }

    /**
     * What the report names as the scan date and the target, for the SMO/Tech Lead to confirm (story 25). Report content, so
     * the read is audited for every role; 404 for a file that has not passed every stage.
     */
    @GetMapping("/api/submissions/{id}/files/{fileId}/metadata")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<ReportFileMetadataView> metadata(@PathVariable UUID id, @PathVariable UUID fileId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(files.metadata(id, fileId));
    }

    /**
     * The file's bytes as an attachment, only once it has passed every stage and only to someone who may see the System;
     * otherwise 404. Every file is served the same way, supporting evidence (an HTML report is active content) included: as
     * an attachment, with the generic content type, no sniffing and a policy that allows nothing, so a browser saves it and
     * never renders it, and the application has no inline view of a file anywhere.
     */
    @GetMapping("/api/submissions/{id}/files/{fileId}/content")
    @PreAuthorize("hasAnyRole('SMO','CYBER_MANAGER','VA_POC')")
    public ResponseEntity<byte[]> content(@PathVariable UUID id, @PathVariable UUID fileId) {
        ReportFiles.Content content = files.content(id, fileId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(content.name(), java.nio.charset.StandardCharsets.UTF_8)
                                .build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noStore())
                .body(content.bytes());
    }
}
