package io.github.lz007001cn.qatrack.service.command;

import io.github.lz007001cn.qatrack.model.Priority;
import java.util.List;

public record CreateTestCaseCommand(Long projectId, String title, String description, String preconditions,
                                    Priority priority, List<TestStepInput> steps) {
    public CreateTestCaseCommand { steps = steps == null ? null : List.copyOf(steps); }
}
