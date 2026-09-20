package io.github.lz007001cn.qatrack.service.command;

import io.github.lz007001cn.qatrack.model.TestPlanStatus;

public record UpdateTestPlanCommand(Long testPlanId, String name, String description,
                                    TestPlanStatus status, Integer lockVersion) { }
