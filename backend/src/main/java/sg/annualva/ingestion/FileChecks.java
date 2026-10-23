package sg.annualva.ingestion;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import sg.annualva.finding.ReportType;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.UnparseableReportException;

/**
 * The stages a file goes through once the scanner has passed it, in their fixed order: the type allowlist, the schema
 * check, the parse with the pre-fill of the scan date and target, and the age check (stories 20a, 20b, 21, 24, 25 and 26):
 * a report that says it was scanned after today's business date is REJECTED_FUTURE_DATED, having passed the schema check
 * and the parse (a report that names no date is not refused for it). A file that fails a stage never reaches
 * the next, and the stages it did pass are the ones it is recorded as having entered. Nothing is stored here: the caller
 * keeps the file's clean copy and writes the outcome only for a file that passed.
 */
final class FileChecks {

    /**
     * What the checks decided.
     *
     * @param stages the statuses the file passed through, in order, after the malware scan; the last is where it rests (a
     *     REJECTED_ one for a file that failed a stage)
     * @param rejection why it was refused; null for a file that passed every stage
     * @param format what the bytes were found to be (a {@link ReportFormat} or an {@link EvidenceFormat} name); null when the
     *     type check refused it
     * @param parsed what the parser read; null unless the file was parsed
     */
    record Outcome(
            List<PipelineStatus> stages, @Nullable RejectCode rejection, @Nullable String format,
            @Nullable ParsedReport parsed) {

        boolean passed() {
            return rejection == null;
        }
    }

    private FileChecks() {}

    /**
     * @param afterToday whether an instant falls on a business date after today's: the age check, read from the clock by
     *     the caller, so that this stays a pure function of what it is given
     */
    static Outcome run(ReportType reportType, FileKind kind, byte[] content, Predicate<Instant> afterToday) {
        if (kind == FileKind.EVIDENCE) {
            return evidence(content);
        }
        TypeCheck.Result typed = TypeCheck.check(reportType, content);
        RejectCode refusedType = typed.rejection();
        if (refusedType != null) {
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.rejected(refusedType)), refusedType,
                    null, null);
        }
        ReportFormat format = Objects.requireNonNull(typed.format());
        Optional<RejectCode> refusedSchema = SchemaCheck.check(format, content);
        if (refusedSchema.isPresent()) {
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK,
                    PipelineStatus.rejected(refusedSchema.get())), refusedSchema.get(), format.name(), null);
        }
        try {
            ParsedReport parsed = format.parser().parse(content);
            Instant scanned = parsed.metadata().scanDate();
            if (scanned != null && afterToday.test(scanned)) {
                // The age check reads the date the parse pre-filled; its Findings are not kept.
                return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK, PipelineStatus.SCHEMA_OK,
                        PipelineStatus.REJECTED_FUTURE_DATED), RejectCode.FUTURE_DATED, format.name(), null);
            }
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK, PipelineStatus.SCHEMA_OK,
                    PipelineStatus.PARSED), null, format.name(), parsed);
        } catch (UnparseableReportException e) {
            // The structure the format needs was not there (or the file holds more than one file may), so the file never
            // counts as having passed the schema check.
            RejectCode code = e.reason() == UnparseableReportException.Reason.OVER_A_LIMIT ? RejectCode.TOO_MANY_FINDINGS
                    : RejectCode.SCHEMA;
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK, PipelineStatus.rejected(code)),
                    code, format.name(), null);
        }
    }

    /**
     * Supporting evidence (ADR 0026): its leading bytes say it is a PDF or an HTML document and it is within the limit for
     * that, or it is refused (a machine-readable report, which starts with an XML declaration and a root that is not
     * {@code html}, or a JSON object, is neither). Nothing else is done with it: no schema check, no parse and no reader
     * of any kind, so nothing in it can reach a log, an audit entry or an error.
     */
    private static Outcome evidence(byte[] content) {
        Optional<EvidenceFormat> sniffed = EvidenceFormat.sniff(content);
        if (sniffed.isEmpty()) {
            return refusedEvidence(RejectCode.TYPE, null);
        }
        EvidenceFormat format = sniffed.get();
        if (content.length > format.maxBytes()) {
            return refusedEvidence(RejectCode.TOO_LARGE, format.name());
        }
        if (format == EvidenceFormat.PDF && EvidenceFormat.carriesEncryptName(content)) {
            return refusedEvidence(RejectCode.ENCRYPTED, format.name());
        }
        return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK, PipelineStatus.READY), null,
                format.name(), null);
    }

    private static Outcome refusedEvidence(RejectCode code, @Nullable String format) {
        return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.rejected(code)), code, format, null);
    }
}
