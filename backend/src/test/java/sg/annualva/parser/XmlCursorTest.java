package sg.annualva.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import sg.annualva.parser.xml.XmlCursor;

/**
 * The contract of the one wrapper every XML parser reads a document through ({@link XmlCursor#read}), which the Dependency-Check,
 * SCAP, Nessus and Burp parsers share: whatever goes wrong inside a walk is the same refusal, with nothing from the file or the
 * reader in it, and a refusal the walk makes itself keeps its reason. The parsers are proved through the HTTP API; this is the
 * one place the wrapper is driven with a walk that fails in ways no real parser does, which the pipeline can never produce.
 */
class XmlCursorTest {

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void anyFailureOfTheWalkIsRefusedAsTheShapeWithNothingFromTheFileOrTheReader() {
        byte[] document = bytes("<a>SENTINEL</a>");

        assertThatThrownBy(() -> XmlCursor.read(document, xml -> {
            throw new IllegalStateException("SENTINEL");
        })).isInstanceOfSatisfying(UnparseableReportException.class, e -> {
            assertThat(e.reason()).isEqualTo(UnparseableReportException.Reason.SHAPE);
            assertThat(e.getMessage()).doesNotContain("SENTINEL");
            assertThat(e.getCause()).isNull();
        });
    }

    @Test
    void aDocumentThatIsNotWellFormedIsRefusedAsTheShapeWithNothingFromTheFile() {
        byte[] document = bytes("<a>SENTINEL</b>");

        assertThatThrownBy(() -> XmlCursor.read(document, xml -> {
            while (xml.next() != null) {
                // read to the end
            }
            return 1;
        })).isInstanceOfSatisfying(UnparseableReportException.class, e -> {
            assertThat(e.reason()).isEqualTo(UnparseableReportException.Reason.SHAPE);
            assertThat(e.getMessage()).doesNotContain("SENTINEL");
            assertThat(e.getCause()).isNull();
        });
    }

    @Test
    void aRefusalTheWalkMakesItselfKeepsItsReason() {
        byte[] document = bytes("<a/>");

        assertThatThrownBy(() -> XmlCursor.read(document, xml -> {
            throw UnparseableReportException.overALimit();
        })).isInstanceOfSatisfying(UnparseableReportException.class,
                e -> assertThat(e.reason()).isEqualTo(UnparseableReportException.Reason.OVER_A_LIMIT));
    }

    @Test
    void theChildLoopEndsAtTheEndOfTheElementAndRefusesADocumentThatEndsInsideIt() throws Exception {
        int children = XmlCursor.read(bytes("<a><b/><c><d/></c></a>"), xml -> {
            xml.next();
            int count = 0;
            while (xml.nextChild()) {
                count++;
                xml.skipElement();
            }
            return count;
        });

        assertThat(children).isEqualTo(2);
        assertThatThrownBy(() -> XmlCursor.read(bytes("<a><b/>"), xml -> {
            xml.next();
            while (xml.nextChild()) {
                xml.skipElement();
            }
            return 0;
        })).isInstanceOf(UnparseableReportException.class);
    }
}
