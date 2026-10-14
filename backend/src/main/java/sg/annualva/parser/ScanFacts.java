package sg.annualva.parser;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a report says about the scan itself, gathered as a parser reads it so that no format repeats it: the latest date any of
 * its results was made and the hosts it names. The latest date is offered as the scan date and the hosts, distinct and in the
 * order they were named, as the target, for the SMO/Tech Lead to confirm; neither is trusted. A parser builds one of these per
 * file.
 */
public final class ScanFacts {

    /** The longest host name a report may give; a longer one refuses the file (a host is part of what a Finding is read from). */
    public static final int MAX_HOST_CHARS = 500;
    /** The target offered is cut here (the column holds at most 200). */
    private static final int MAX_TARGET_CHARS = 200;
    /** The hosts held for the target; the text is cut at {@link #MAX_TARGET_CHARS} anyway. */
    private static final int MAX_HOSTS_NAMED = 50;

    private final Set<String> hosts = new LinkedHashSet<>();
    private @Nullable Instant scanDate;

    /** A date a result was made; the latest of them is kept. A null (the report gave none usable) changes nothing. */
    public void scanned(@Nullable Instant at) {
        if (at != null && (scanDate == null || at.isAfter(scanDate))) {
            scanDate = at;
        }
    }

    /**
     * A host the report names, already cleaned.
     *
     * @throws UnparseableReportException when the name is longer than {@link #MAX_HOST_CHARS}
     */
    public void named(String host) throws UnparseableReportException {
        if (!ReportText.fits(host, MAX_HOST_CHARS)) {
            throw new UnparseableReportException();
        }
        if (hosts.size() < MAX_HOSTS_NAMED) {
            hosts.add(host);
        }
    }

    /** What the report said: the latest date, and the hosts joined with a comma and cut to a fixed length. */
    public ParsedReport.Metadata metadata() {
        String target = ReportText.cleanAndCut(String.join(", ", hosts), MAX_TARGET_CHARS).orElse(null);
        return new ParsedReport.Metadata(scanDate, target);
    }
}
