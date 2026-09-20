package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.service.importing.*;

public interface TestImportService {
    ImportPreview analyzeImport(Long actorUserId, AnalyzeTestImportCommand command);
    ImportExecutionResult importReport(Long actorUserId, ImportTestResultsCommand command);
}
