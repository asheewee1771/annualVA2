package sg.annualva.parser.dependencycheck;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import sg.annualva.parser.ParsedReport;
import sg.annualva.parser.ReportParser;
import sg.annualva.parser.ReportText;
import sg.annualva.parser.UnparseableReportException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads an OWASP Dependency-Check JSON report: an object with {@code reportSchema}, {@code scanInfo}, {@code projectInfo}
 * (the project's name and the report date) and a {@code dependencies} array. The report is streamed token by token and only
 * the fields that matter are kept (a dependency's file name, path and package ids and each vulnerability's name and
 * ratings); every other value is skipped without being built, so memory is bounded by the Findings a report may yield
 * ({@link ParsedReport#MAX_FINDINGS}) and never by the size of one dependency. A report that lacks {@code reportSchema},
 * {@code scanInfo} or {@code dependencies}, whose {@code dependencies} is not an array of objects, or whose fields have a
 * type they do not have in the format, is refused.
 */
public final class DependencyCheckJsonParser implements ReportParser {

    /** A string longer than {@link ReportText#MAX_FIELD_CHARS} that the parser reads is refused, so no value is built whole. */
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(ReportText.MAX_FIELD_CHARS).build())
            .build()).build();

    @Override
    public ParsedReport parse(byte[] content) throws UnparseableReportException {
        try (JsonParser parser = JSON.createParser(content)) {
            return read(parser);
        } catch (RuntimeException e) {
            // The reader's message may quote the file; it is dropped here.
            throw new UnparseableReportException();
        }
    }

    private static ParsedReport read(JsonParser parser) throws UnparseableReportException {
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            throw new UnparseableReportException();
        }
        DependencyCheckFindings findings = new DependencyCheckFindings();
        boolean sawSchema = false;
        boolean sawScanInfo = false;
        boolean sawDependencies = false;
        @Nullable String projectName = null;
        @Nullable String reportDate = null;
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            switch (name) {
                case "reportSchema" -> {
                    sawSchema = true;
                    parser.skipChildren();
                }
                case "scanInfo" -> {
                    sawScanInfo = true;
                    parser.skipChildren();
                }
                case "projectInfo" -> {
                    if (value != JsonToken.START_OBJECT) {
                        throw new UnparseableReportException();
                    }
                    while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                        String field = parser.currentName();
                        JsonToken fieldValue = parser.nextToken();
                        switch (field) {
                            case "name" -> projectName = string(parser, fieldValue);
                            case "reportDate" -> reportDate = string(parser, fieldValue);
                            default -> parser.skipChildren();
                        }
                    }
                }
                case "dependencies" -> {
                    if (value != JsonToken.START_ARRAY) {
                        throw new UnparseableReportException();
                    }
                    sawDependencies = true;
                    JsonToken next;
                    while ((next = parser.nextToken()) == JsonToken.START_OBJECT) {
                        dependency(parser, findings);
                    }
                    if (next != JsonToken.END_ARRAY) {
                        throw new UnparseableReportException();
                    }
                }
                default -> parser.skipChildren();
            }
        }
        if (!sawSchema || !sawScanInfo || !sawDependencies) {
            throw new UnparseableReportException();
        }
        findings.metadata(projectName, reportDate);
        return findings.build();
    }

    /** One dependency, the parser positioned on its opening brace; it is left on the closing one. */
    private static void dependency(JsonParser parser, DependencyCheckFindings findings)
            throws UnparseableReportException {
        @Nullable String fileName = null;
        @Nullable String filePath = null;
        List<String> packageIds = new ArrayList<>();
        List<DependencyCheckFindings.Vulnerability> vulnerabilities = new ArrayList<>();
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String name = parser.currentName();
            JsonToken value = parser.nextToken();
            switch (name) {
                case "fileName" -> fileName = string(parser, value);
                case "filePath" -> filePath = string(parser, value);
                case "packages" -> packages(parser, value, packageIds);
                case "vulnerabilities" -> vulnerabilities(parser, value, vulnerabilities);
                default -> parser.skipChildren();
            }
        }
        findings.dependency(fileName, filePath, packageIds, vulnerabilities);
    }

    private static void packages(JsonParser parser, JsonToken start, List<String> ids)
            throws UnparseableReportException {
        if (start == JsonToken.VALUE_NULL) {
            return;
        }
        if (start != JsonToken.START_ARRAY) {
            throw new UnparseableReportException();
        }
        JsonToken next;
        while ((next = parser.nextToken()) == JsonToken.START_OBJECT) {
            @Nullable String id = null;
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("id".equals(field)) {
                    id = string(parser, value);
                } else {
                    parser.skipChildren();
                }
            }
            if (id != null) {
                DependencyCheckFindings.addPackage(ids, id);
            }
        }
        if (next != JsonToken.END_ARRAY) {
            throw new UnparseableReportException();
        }
    }

    private static void vulnerabilities(JsonParser parser, JsonToken start,
            List<DependencyCheckFindings.Vulnerability> into) throws UnparseableReportException {
        if (start == JsonToken.VALUE_NULL) {
            return;
        }
        if (start != JsonToken.START_ARRAY) {
            throw new UnparseableReportException();
        }
        JsonToken next;
        while ((next = parser.nextToken()) == JsonToken.START_OBJECT) {
            @Nullable String name = null;
            Rating v3 = new Rating();
            Rating v2 = new Rating();
            while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                switch (field) {
                    case "name" -> name = string(parser, value);
                    case "cvssv3", "cvssV3" -> v3 = rating(parser, value, "baseScore", "baseSeverity");
                    case "cvssv2", "cvssV2" -> v2 = rating(parser, value, "score", "severity");
                    default -> parser.skipChildren();
                }
            }
            DependencyCheckFindings.addVulnerability(into,
                    new DependencyCheckFindings.Vulnerability(name, v3.score, v3.label, v2.score, v2.label));
        }
        if (next != JsonToken.END_ARRAY) {
            throw new UnparseableReportException();
        }
    }

    /** A CVSS block's score and rating word. */
    private static final class Rating {
        @Nullable Double score;
        @Nullable String label;
    }

    /** The score and the rating word of a CVSS object; any other value is no rating. */
    private static Rating rating(JsonParser parser, JsonToken start, String scoreField, String labelField) {
        Rating rating = new Rating();
        if (start != JsonToken.START_OBJECT) {
            parser.skipChildren();
            return rating;
        }
        while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
            String field = parser.currentName();
            JsonToken value = parser.nextToken();
            if (scoreField.equals(field)) {
                rating.score = number(parser, value);
            } else if (labelField.equals(field)) {
                rating.label = string(parser, value);
            } else {
                parser.skipChildren();
            }
        }
        return rating;
    }

    /** The value as text when it is a string; any other value is skipped and is no text. */
    private static @Nullable String string(JsonParser parser, JsonToken value) {
        if (value == JsonToken.VALUE_STRING) {
            return parser.getString();
        }
        parser.skipChildren();
        return null;
    }

    /** A score the report writes as a number (or, as some versions do, as text); null for anything else. */
    private static @Nullable Double number(JsonParser parser, JsonToken value) {
        if (value == JsonToken.VALUE_NUMBER_INT || value == JsonToken.VALUE_NUMBER_FLOAT) {
            double score = parser.getDoubleValue();
            return Double.isFinite(score) ? score : null;
        }
        if (value == JsonToken.VALUE_STRING) {
            return DependencyCheckFindings.score(parser.getString());
        }
        parser.skipChildren();
        return null;
    }
}
