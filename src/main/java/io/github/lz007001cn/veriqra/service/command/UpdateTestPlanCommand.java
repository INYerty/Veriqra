package io.github.lz007001cn.veriqra.service.command;

import io.github.lz007001cn.veriqra.model.TestPlanStatus;

public record UpdateTestPlanCommand(Long testPlanId, String name, String description,
                                    TestPlanStatus status, Integer lockVersion) { }
