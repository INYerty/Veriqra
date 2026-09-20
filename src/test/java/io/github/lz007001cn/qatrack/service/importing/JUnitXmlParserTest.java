package io.github.lz007001cn.qatrack.service.importing;

import io.github.lz007001cn.qatrack.model.TestAttemptStatus;
import io.github.lz007001cn.qatrack.service.exception.ValidationException;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class JUnitXmlParserTest {
    private final JUnitXmlParser parser = new JUnitXmlParser();

    @Test void parsesPassFailureErrorSkippedAndDurations() {
        JUnitParseResult report = parser.parse(bytes("""
                <testsuite name="suite">
                  <testcase classname="example.LoginTest" name="pass" time="0.125"/>
                  <testcase classname="example.LoginTest" name="failure"><failure message="wrong">trace</failure></testcase>
                  <testcase classname="example.LoginTest" name="error"><error message="boom">stack</error></testcase>
                  <testcase classname="example.LoginTest" name="skip"><skipped message="disabled"/></testcase>
                </testsuite>
                """));
        assertTrue(report.invalidEntries().isEmpty());
        assertEquals(4, report.results().size());
        assertEquals(TestAttemptStatus.PASS, report.results().get(0).status());
        assertEquals(125L, report.results().get(0).durationMs());
        assertEquals(TestAttemptStatus.FAIL, report.results().get(1).status());
        assertTrue(report.results().get(1).failureMessage().startsWith("[JUnit failure] wrong"));
        assertTrue(report.results().get(2).failureMessage().startsWith("[JUnit error] boom"));
        assertEquals(TestAttemptStatus.SKIPPED, report.results().get(3).status());
        assertEquals("disabled", report.results().get(3).comment());
    }

    @Test void parsesNestedTestsuitesAndReportsEntryErrorsWithoutInventingResults() {
        JUnitParseResult report = parser.parse(bytes("""
                <testsuites><testsuite name="outer"><testsuite name="inner">
                  <testcase classname="example.Nested" name="one"/>
                  <testcase classname="" name="missing"/>
                  <testcase classname="example.Nested" name="one"/>
                  <testcase classname="example.Nested" name="bad-time" time="nope"/>
                </testsuite></testsuite></testsuites>
                """));
        assertEquals(1, report.results().size());
        assertEquals(3, report.invalidEntries().size());
        assertEquals("one", report.results().getFirst().identity().externalKey());
    }

    @Test void malformedXmlIsRejected() {
        assertThrows(ValidationException.class,
                () -> parser.parse(bytes("<testsuite><testcase></testsuite>")));
    }

    @Test void doctypeAndExternalEntityAreRejectedBeforeResolution() {
        String xxe = """
                <!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "file:///definitely-not-readable/qatrack-secret">]>
                <testsuite><testcase classname="example.Security" name="xxe"><failure>&xxe;</failure></testcase></testsuite>
                """;
        assertThrows(ValidationException.class, () -> parser.parse(bytes(xxe)));
    }

    @Test void externalNetworkEntityIsRejectedWithoutOpeningAConnection() throws Exception {
        try (var listener = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listener.setSoTimeout(250);
            String xxe = "<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM \"http://127.0.0.1:"
                    + listener.getLocalPort() + "/secret\">]>"
                    + "<testsuite><testcase classname=\"example.Security\" name=\"network\">"
                    + "<failure>&xxe;</failure></testcase></testsuite>";
            assertThrows(ValidationException.class, () -> parser.parse(bytes(xxe)));
            assertThrows(SocketTimeoutException.class, listener::accept);
        }
    }

    @Test void oversizedFailureTextIsUtf8SafelyTruncatedForMysqlText() {
        String body = "测".repeat(30_000);
        JUnitTestResult result = parser.parse(bytes("<testsuite><testcase classname=\"example.Large\" name=\"one\">"
                + "<failure>" + body + "</failure></testcase></testsuite>")).results().getFirst();
        assertTrue(result.failureMessage().getBytes(StandardCharsets.UTF_8).length <= 65_535);
        assertFalse(result.failureMessage().endsWith("\uFFFD"));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
