package sg.annualva.ingestion;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import sg.annualva.finding.ReportType;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.ReportParser;
import sg.annualva.parser.UnparseableReportException;

/**
 * The stages a file goes through once the scanner has passed it, in their fixed order: the type allowlist, the schema
 * check, and, for a format that has a parser, the parse (stories 20a, 20b, 21 and 24). A file that fails a stage never reaches
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
     * @param format what the bytes were found to be; null when the type check refused it
     * @param parsed what the parser read; null unless the file was parsed
     */
    record Outcome(
            List<PipelineStatus> stages, @Nullable RejectCode rejection, @Nullable ReportFormat format,
            @Nullable ParsedReport parsed) {

        boolean passed() {
            return rejection == null;
        }
    }

    private FileChecks() {}

    static Outcome run(ReportType reportType, FileKind kind, byte[] content) {
        TypeCheck.Result typed = TypeCheck.check(reportType, kind, content);
        RejectCode refusedType = typed.rejection();
        if (refusedType != null) {
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.rejected(refusedType)), refusedType,
                    null, null);
        }
        ReportFormat format = Objects.requireNonNull(typed.format());
        Optional<RejectCode> refusedSchema = SchemaCheck.check(format, content);
        if (refusedSchema.isPresent()) {
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK,
                    PipelineStatus.rejected(refusedSchema.get())), refusedSchema.get(), format, null);
        }
        ReportParser parser = format.parser();
        if (parser == null) {
            // Its parser arrives with its own ticket: the file waits at SCHEMA_OK until then.
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK, PipelineStatus.SCHEMA_OK), null,
                    format, null);
        }
        try {
            ParsedReport parsed = parser.parse(content);
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK, PipelineStatus.SCHEMA_OK,
                    PipelineStatus.PARSED), null, format, parsed);
        } catch (UnparseableReportException e) {
            // The structure the format needs was not there (or the file holds more than one file may), so the file never
            // counts as having passed the schema check.
            RejectCode code = e.reason() == UnparseableReportException.Reason.OVER_A_LIMIT ? RejectCode.TOO_MANY_FINDINGS
                    : RejectCode.SCHEMA;
            return new Outcome(List.of(PipelineStatus.SCAN_CLEAN, PipelineStatus.TYPE_OK, PipelineStatus.rejected(code)),
                    code, format, null);
        }
    }
}
