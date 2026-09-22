package io.github.lz007001cn.veriqra.service.command;

import java.util.List;

/** Initial plan scope is optional; order is derived from TestCase key_no because schema has no order column. */
public record CreateTestPlanCommand(Long projectId, String name, String description, List<Long> testCaseIds) {
    public CreateTestPlanCommand { testCaseIds = testCaseIds == null ? null : List.copyOf(testCaseIds); }
}
