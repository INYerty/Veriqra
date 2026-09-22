package io.github.lz007001cn.veriqra.service.importing;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.exception.ValidationException;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import java.io.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Minimal JUnit XML reader with external resource access disabled. */
public final class JUnitXmlParser {
    static final int MAX_PAYLOAD_BYTES = 5 * 1024 * 1024;
    static final int MAX_TESTCASES = 10_000;
    private static final int MYSQL_TEXT_BYTES = 65_535;

    public JUnitParseResult parse(byte[] payload) {
        if (payload == null || payload.length == 0) throw new ValidationException("JUnit XML payload is required");
        if (payload.length > MAX_PAYLOAD_BYTES) throw new ValidationException("JUnit XML payload exceeds 5 MiB");
        Document document = parseSecurely(payload);
        Element root = document.getDocumentElement();
        if (root == null || !("testsuite".equals(root.getTagName()) || "testsuites".equals(root.getTagName()))) {
            throw new ValidationException("JUnit XML root must be testsuite or testsuites");
        }
        NodeList nodes = root.getElementsByTagName("testcase");
        if (nodes.getLength() == 0) throw new ValidationException("JUnit XML contains no testcase entries");
        if (nodes.getLength() > MAX_TESTCASES) throw new ValidationException("JUnit XML contains too many testcase entries");

        List<JUnitTestResult> results = new ArrayList<>();
        List<ImportIssue> issues = new ArrayList<>();
        Set<AutomationIdentityKey> identities = new HashSet<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element testcase = (Element) nodes.item(i);
            int entry = i + 1;
            String namespace = testcase.getAttribute("classname");
            String externalKey = testcase.getAttribute("name");
            if (namespace.isBlank() || namespace.codePointCount(0, namespace.length()) > 128) {
                issues.add(new ImportIssue(entry, "testcase classname is required and limited to 128 characters"));
                continue;
            }
            if (externalKey.isEmpty() || externalKey.codePointCount(0, externalKey.length()) > 512) {
                issues.add(new ImportIssue(entry, "testcase name is required and limited to 512 characters"));
                continue;
            }
            AutomationIdentityKey identity = new AutomationIdentityKey(AutomationSource.JUNIT, namespace, externalKey);
            if (!identities.add(identity)) {
                issues.add(new ImportIssue(entry, "duplicate automation identity in one report"));
                continue;
            }
            try {
                Long duration = durationMillis(testcase.getAttribute("time"));
                Outcome outcome = outcome(testcase);
                results.add(new JUnitTestResult(entry, identity, outcome.status(), duration,
                        outcome.comment(), outcome.failureMessage()));
            } catch (IllegalArgumentException failure) {
                issues.add(new ImportIssue(entry, failure.getMessage()));
            }
        }
        return new JUnitParseResult(results, issues);
    }

    private static Document parseSecurely(byte[] payload) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("External XML resources are disabled"); });
            builder.setErrorHandler(new DefaultHandler());
            return builder.parse(new ByteArrayInputStream(payload));
        } catch (ParserConfigurationException failure) {
            throw new IllegalStateException("Secure XML parser configuration is unavailable", failure);
        } catch (SAXException | IOException failure) {
            throw new ValidationException("Invalid or unsafe JUnit XML", failure);
        }
    }

    private static Long durationMillis(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            BigDecimal seconds = new BigDecimal(value);
            if (seconds.signum() < 0) throw new IllegalArgumentException("testcase duration must be non-negative");
            return seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (NumberFormatException | ArithmeticException failure) {
            throw new IllegalArgumentException("testcase duration is invalid");
        }
    }

    private static Outcome outcome(Element testcase) {
        Element failure = directChild(testcase, "failure");
        Element error = directChild(testcase, "error");
        Element skipped = directChild(testcase, "skipped");
        int markers = (failure == null ? 0 : 1) + (error == null ? 0 : 1) + (skipped == null ? 0 : 1);
        if (markers > 1) throw new IllegalArgumentException("testcase has conflicting outcome elements");
        if (error != null) return new Outcome(TestAttemptStatus.FAIL, null,
                failureText("[JUnit error]", error));
        if (failure != null) return new Outcome(TestAttemptStatus.FAIL, null,
                failureText("[JUnit failure]", failure));
        if (skipped != null) return new Outcome(TestAttemptStatus.SKIPPED,
                nullableText(skipped), null);
        return new Outcome(TestAttemptStatus.PASS, null, null);
    }

    private static Element directChild(Element parent, String name) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) return element;
        }
        return null;
    }

    private static String failureText(String prefix, Element element) {
        String message = element.getAttribute("message");
        String body = element.getTextContent();
        String value = prefix + (message.isBlank() ? "" : " " + message)
                + (body == null || body.isBlank() ? "" : "\n" + body.strip());
        return truncateUtf8(value, MYSQL_TEXT_BYTES);
    }

    private static String nullableText(Element element) {
        String message = element.getAttribute("message");
        String body = element.getTextContent();
        String value = !message.isBlank() ? message : body == null ? null : body.strip();
        return value == null || value.isBlank() ? null : truncateUtf8(value, MYSQL_TEXT_BYTES);
    }

    static String truncateUtf8(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
        int bytes = 0;
        int end = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maxBytes) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
    }

    private record Outcome(TestAttemptStatus status, String comment, String failureMessage) { }
}
