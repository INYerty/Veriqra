package io.github.lz007001cn.qatrack.service.command;

import io.github.lz007001cn.qatrack.model.*;
import java.util.List;

public record UpdateTestCaseCommand(Long testCaseId, String title, String description, String preconditions,
                                    Priority priority, TestCaseStatus status, Integer lockVersion,
                                    List<TestStepInput> steps) {
    public UpdateTestCaseCommand { steps = steps == null ? null : List.copyOf(steps); }
}
