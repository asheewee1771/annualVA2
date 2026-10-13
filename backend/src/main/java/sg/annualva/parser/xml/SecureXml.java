package sg.annualva.parser.xml;

import java.io.ByteArrayInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * The only place an XML reader is made (the OWASP rules against XML external entities). Report files are untrusted: the
 * reader never loads a DTD, an external entity or a schema, so a document cannot make the server fetch or expand
 * anything. A DOCTYPE is skipped, not obeyed (Burp's export carries one), and an entity reference it would have defined
 * is an error. An architecture test keeps every other class from making an XML factory.
 */
public final class SecureXml {

    private SecureXml() {}

    /**
     * A streaming reader over the bytes, positioned before the first event.
     *
     * @throws XMLStreamException when the reader cannot be made; a malformed document fails later, as it is read
     */
    public static XMLStreamReader reader(byte[] content) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.createXMLStreamReader(new ByteArrayInputStream(content));
    }
}
