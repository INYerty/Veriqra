package io.github.lz007001cn.veriqra.service.importing;

import io.github.lz007001cn.veriqra.model.*;
import java.util.List;

public record ImportPreview(List<MappedResult> mappedResults,
                            List<AutomationIdentityKey> unmappedIdentities,
                            List<ImportIssue> invalidEntries) {
    public ImportPreview {
        mappedResults = List.copyOf(mappedResults);
        unmappedIdentities = List.copyOf(unmappedIdentities);
        invalidEntries = List.copyOf(invalidEntries);
    }

    public boolean readyToImport() { return !mappedResults.isEmpty() && unmappedIdentities.isEmpty() && invalidEntries.isEmpty(); }

    public record MappedResult(JUnitTestResult result, TestAutomationIdentity identity,
                               TestAutomationMapping mapping, TestCase testCase) { }
}
