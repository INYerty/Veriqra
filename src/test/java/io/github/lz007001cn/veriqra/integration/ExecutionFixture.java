package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.UUID;

/** Test data builders only, using the approved isolated schema. */
abstract class ExecutionFixture extends AssetFixture {
    protected record Execution(Parents parents, TestCase testCase, TestRun run, TestRunCase runCase) { }
    protected Execution execution(Connection c) {
        var p = parents(c);
        var t = new JdbcTestCaseDao(c).insert(testCase(p, 1));
        var r = new JdbcTestRunDao(c).insert(run(p, null));
        var rc = new JdbcTestRunCaseDao(c).insert(snapshot(r.id(), t));
        return new Execution(p, t, r, rc);
    }
    protected TestRun run(Parents p, Long planId) {
        return new TestRun(null, p.projectId(), planId, "Run ' ? 中文", null, null,
                TestRunStatus.IN_PROGRESS, null, p.userId(), null, null, null);
    }
    protected TestRunCase snapshot(Long runId, TestCase t) {
        return new TestRunCase(null, runId, t.id(), t.title(), t.description(), t.preconditions(), t.priority(), null);
    }
    protected TestAttempt attempt(Execution e, int number, TestAttemptStatus status) {
        return new TestAttempt(null, e.runCase().id(), number, status, e.parents().userId(), null, null,
                LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123456000), null, 123L, "comment ' ? 中文",
                status == TestAttemptStatus.FAIL ? "failure ' ? 中文" : null, UUID.randomUUID());
    }
    protected Defect defect(Parents p, long key) {
        return new Defect(null, p.projectId(), key, "Bug ' ? 中文", null, DefectSeverity.MEDIUM,
                Priority.HIGH, DefectStatus.OPEN, p.userId(), null, null, null, null, null);
    }
}
