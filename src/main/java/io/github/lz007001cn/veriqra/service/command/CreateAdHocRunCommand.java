package io.github.lz007001cn.veriqra.service.command;

import java.util.List;

public record CreateAdHocRunCommand(Long projectId, String name, String environment,
                                    String buildVersion, List<Long> testCaseIds) {
    public CreateAdHocRunCommand { testCaseIds = testCaseIds == null ? null : List.copyOf(testCaseIds); }
}
