package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.service.command.*;
import io.github.lz007001cn.veriqra.service.importing.*;

public interface TestImportService {
    ImportPreview analyzeImport(Long actorUserId, AnalyzeTestImportCommand command);
    ImportExecutionResult importReport(Long actorUserId, ImportTestResultsCommand command);
}
