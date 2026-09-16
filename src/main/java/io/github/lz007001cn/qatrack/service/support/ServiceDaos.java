package io.github.lz007001cn.qatrack.service.support;

import io.github.lz007001cn.qatrack.dao.*;

/** Connection-scoped DAO set. Every DAO in one instance shares the caller's transaction. */
public record ServiceDaos(UserDao users, ProjectDao projects, ProjectMemberDao members,
                           ProjectCounterDao counters, RequirementDao requirements,
                           TestCaseDao testCases, TestStepDao steps,
                           TestCaseRequirementDao traceability, TestRunDao testRuns) { }
