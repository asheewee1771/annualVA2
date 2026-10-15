package sg.annualva.parser;

/**
 * What the parser of a scan report holds while it reads one file, so that no format declares it again: the Findings so far,
 * what the file says about the scan itself, and whether the file had any section of the shape its format has (a {@code Report}
 * of a Nessus audit, a {@code TestResult} of a SCAP scan, the root of a Burp export). A parser makes one per file, feeds
 * {@link #findings()} and {@link #facts()} and asks for {@link #build()} when it has read the whole file.
 */
public final class ScanReading {

    private final FindingsBuilder findings = new FindingsBuilder();
    private final ScanFacts facts = new ScanFacts();
    private int sections;

    /** Where the Findings are recorded; it counts and merges them, the only place that does. */
    public FindingsBuilder findings() {
        return findings;
    }

    /** The latest scan date and the hosts the file names. */
    public ScanFacts facts() {
        return facts;
    }

    /** The file has one section of the shape its format needs. */
    public void sawSection() {
        sections++;
    }

    /**
     * The merged Findings with the metadata the file gave.
     *
     * @throws UnparseableReportException when the file had no section of the shape its format needs: it is not a report of
     *     that format, whatever its root was called
     */
    public ParsedReport build() throws UnparseableReportException {
        if (sections == 0) {
            throw new UnparseableReportException();
        }
        return findings.build(facts.metadata());
    }
}
