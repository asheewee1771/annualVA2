package sg.annualva.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import sg.annualva.parser.burp.BurpXmlParser;
import sg.annualva.parser.dependencycheck.DependencyCheckXmlParser;
import sg.annualva.parser.scap.ScapXmlParser;
import sg.annualva.parser.xml.XmlCursor;

/**
 * IM8 as-1 at the XML parsers themselves: the upload pipeline's schema check already refuses a document nested deeper than
 * {@link XmlCursor#MAX_DEPTH}, but a parser must not rely on its caller for that, so a deeply nested document handed
 * straight to one is refused, whether its walk enters the nesting or skips it. This is the one test that drives a parser
 * without the HTTP API, because the pipeline never lets such a file reach it.
 */
class XmlParserDepthTest {

    private static final String XCCDF = "http://checklists.nist.gov/xccdf/1.2";
    private static final String DEPENDENCY_CHECK = "https://jeremylong.github.io/DependencyCheck/dependency-check.2.5.xsd";

    private static final String RESULT = "<TestResult id=\"t\"><target>h.invalid</target>"
            + "<rule-result idref=\"r.1\"><result>fail</result></rule-result></TestResult>";

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** A SCAP Benchmark (the first level) with its result, and this many nested groups, which the parser skips. */
    private static byte[] scapWithGroups(int groups) {
        return bytes("<Benchmark xmlns=\"" + XCCDF + "\">" + "<Group>".repeat(groups) + "</Group>".repeat(groups) + RESULT
                + "</Benchmark>");
    }

    @Test
    void aScapSubtreeTheParserSkipsIsRefusedWhenDeeperThanTheLimitAndReadAtTheLimit() throws Exception {
        ScapXmlParser parser = new ScapXmlParser();

        ParsedReport atTheLimit = parser.parse(scapWithGroups(XmlCursor.MAX_DEPTH - 1));

        assertThat(atTheLimit.findings()).hasSize(1);
        assertThatThrownBy(() -> parser.parse(scapWithGroups(XmlCursor.MAX_DEPTH)))
                .isInstanceOf(UnparseableReportException.class);
        assertThatThrownBy(() -> parser.parse(scapWithGroups(150_000))).isInstanceOfSatisfying(
                UnparseableReportException.class,
                e -> assertThat(e.reason()).isEqualTo(UnparseableReportException.Reason.SHAPE));
    }

    /** A Burp export (the first level) with one issue (the second) whose request and response (the third) nest this deep. */
    private static byte[] burpWithNesting(int levels) {
        return bytes("<issues><issue><type>1</type><host>https://h.invalid</host><requestresponse>" + "<a>".repeat(levels)
                + "</a>".repeat(levels) + "</requestresponse></issue></issues>");
    }

    @Test
    void aBurpSubtreeTheParserSkipsIsRefusedWhenDeeperThanTheLimitAndReadAtTheLimit() throws Exception {
        BurpXmlParser parser = new BurpXmlParser();
        int room = XmlCursor.MAX_DEPTH - 3;

        ParsedReport atTheLimit = parser.parse(burpWithNesting(room));

        assertThat(atTheLimit.findings()).hasSize(1);
        assertThatThrownBy(() -> parser.parse(burpWithNesting(room + 1))).isInstanceOf(UnparseableReportException.class);
        assertThatThrownBy(() -> parser.parse(burpWithNesting(150_000))).isInstanceOf(UnparseableReportException.class);
    }

    @Test
    void aDependencyCheckElementTheWalkEntersDeeperThanTheLimitIsRefusedAndOneAtTheLimitIsRead() throws Exception {
        DependencyCheckXmlParser parser = new DependencyCheckXmlParser();
        // analysis and dependencies are the first two levels; the rest are unknown elements the parser walks into one by one.
        String open = "<analysis xmlns=\"" + DEPENDENCY_CHECK + "\"><dependencies>";
        String close = "</dependencies></analysis>";
        int room = XmlCursor.MAX_DEPTH - 2;

        ParsedReport atTheLimit = parser.parse(bytes(open + "<a>".repeat(room) + "</a>".repeat(room) + close));

        assertThat(atTheLimit.findings()).isEmpty();
        assertThatThrownBy(() -> parser.parse(bytes(open + "<a>".repeat(room + 1) + "</a>".repeat(room + 1) + close)))
                .isInstanceOf(UnparseableReportException.class);
        assertThatThrownBy(() -> parser.parse(bytes(open + "<a>".repeat(150_000) + "</a>".repeat(150_000) + close)))
                .isInstanceOf(UnparseableReportException.class);
    }
}
