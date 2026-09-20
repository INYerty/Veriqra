package io.github.lz007001cn.qatrack.service.importing;

import io.github.lz007001cn.qatrack.model.*;
import java.util.List;

public record ImportExecutionResult(TestImport testImport, TestRun testRun,
                                    List<TestRunCase> runCases, List<TestAttempt> attempts,
                                    boolean replayed) {
    public ImportExecutionResult {
        runCases = List.copyOf(runCases);
        attempts = List.copyOf(attempts);
    }
}
