package io.github.lz007001cn.veriqra.service.command;

public record UpdateProjectCommand(Long projectId, String name, String description, Integer lockVersion) { }
