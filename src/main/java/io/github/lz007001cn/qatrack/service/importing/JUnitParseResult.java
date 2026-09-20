package io.github.lz007001cn.qatrack.service.importing;

import java.util.List;

public record JUnitParseResult(List<JUnitTestResult> results, List<ImportIssue> invalidEntries) {
    public JUnitParseResult {
        results = List.copyOf(results);
        invalidEntries = List.copyOf(invalidEntries);
    }
}
