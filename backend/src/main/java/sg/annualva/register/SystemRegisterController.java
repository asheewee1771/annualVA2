package sg.annualva.register;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** The System Register, for the VA POCs only: the template to fill in now, the upload and its preview in later tickets. */
@RestController
public class SystemRegisterController {

    static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final RegisterTemplates templates;

    SystemRegisterController(RegisterTemplates templates) {
        this.templates = templates;
    }

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
}
