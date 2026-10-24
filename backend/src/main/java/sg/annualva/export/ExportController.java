package sg.annualva.export;

import java.net.URI;
import java.util.List;
import java.util.UUID;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Exports, generated in the background and then downloaded (story 81, spec "API contract"). The management summary is the
 * VA POCs' (the permission matrix); an export is read and downloaded by its requester alone.
 */
@RestController
public class ExportController {

    private final Exports exports;

    ExportController(Exports exports) {
        this.exports = exports;
    }

    /** Asks for an export (a step-up action): 202 with its address, to be read until it is ready. */
    @PostMapping("/api/exports")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<Exports.ExportView> request(@RequestBody Exports.ExportRequest request) {
        Exports.ExportView queued = exports.request(request);
        return ResponseEntity.accepted().location(URI.create("/api/exports/" + queued.id()))
                .cacheControl(CacheControl.noStore()).body(queued);
    }

    /** The caller's exports of the last 48 hours, newest first. */
    @GetMapping("/api/exports")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<List<Exports.ExportView>> mine() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exports.mine());
    }

    /** Where one of the caller's exports is; anyone else's is 404. */
    @GetMapping("/api/exports/{id}")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<Exports.ExportView> status(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(exports.status(id));
    }

    /**
     * The file of one of the caller's ready exports, as an attachment that is never sniffed or rendered; the download is
     * audited. 409 before it is ready or after it failed, 410 once its 24 hours are up, 404 for anyone else's.
     */
    @GetMapping("/api/exports/{id}/download")
    @PreAuthorize("hasRole('VA_POC')")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        Exports.Download file = exports.download(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .cacheControl(CacheControl.noStore())
                .body(file.content());
    }
}
