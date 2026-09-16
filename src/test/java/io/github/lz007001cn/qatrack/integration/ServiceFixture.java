package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.dao.jdbc.*;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.*;
import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.service.support.*;
import org.junit.jupiter.api.BeforeEach;
import java.time.*;

abstract class ServiceFixture extends MysqlFixture {
    protected JdbcServiceDaoFactory jdbcDaos;
    protected JdbcServiceTransaction serviceTx;
    protected ProjectAccessPolicy access;
    protected ProjectService projects;
    protected RequirementService requirements;
    protected TestCaseService testCases;
    protected TraceabilityService traceability;
    protected final Clock clock = Clock.fixed(Instant.parse("2026-09-16T00:00:00Z"), ZoneOffset.UTC);

    @BeforeEach void configureServices() {
        jdbcDaos = new JdbcServiceDaoFactory();
        serviceTx = new JdbcServiceTransaction(tx);
        access = new ProjectAccessPolicy();
        projects = new DefaultProjectService(serviceTx, jdbcDaos, access);
        requirements = new DefaultRequirementService(serviceTx, jdbcDaos, access);
        testCases = new DefaultTestCaseService(serviceTx, jdbcDaos, access);
        traceability = new DefaultTraceabilityService(serviceTx, jdbcDaos, access, clock);
    }

    protected User actor(String username, SystemRole role, UserStatus status) {
        return tx.inTransaction(c -> new JdbcUserDao(c).insert(new User(null, username, username,
                "fixture-hash-not-for-login", role, status, null, null, null)));
    }

    protected Project createProject(User admin, String key, User initialMember, ProjectRole role) {
        return projects.create(admin.id(), new CreateProjectCommand(key, key + " project", null,
                initialMember == null ? null : initialMember.id(), initialMember == null ? null : role));
    }

    protected Requirement createRequirement(User actor, Project project, String title) {
        return requirements.create(actor.id(), new CreateRequirementCommand(project.id(), title, "description", Priority.MEDIUM));
    }

    protected TestCase createCase(User actor, Project project, String title, int stepCount) {
        var steps = java.util.stream.IntStream.rangeClosed(1, stepCount)
                .mapToObj(i -> new TestStepInput(i, "action " + i, "expected " + i)).toList();
        return testCases.create(actor.id(), new CreateTestCaseCommand(project.id(), title, "description",
                "preconditions", Priority.MEDIUM, steps));
    }

    protected void addMember(Project project, User user, ProjectRole role, MembershipStatus status) {
        tx.inTransaction(c -> {
            new JdbcProjectMemberDao(c).add(new ProjectMember(project.id(), user.id(), role, status, null, null, null));
            return null;
        });
    }
}
