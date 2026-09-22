package io.github.lz007001cn.veriqra.service.command;

public record CreatePlanRunCommand(Long projectId, Long testPlanId, String name,
                                   String environment, String buildVersion) { }
