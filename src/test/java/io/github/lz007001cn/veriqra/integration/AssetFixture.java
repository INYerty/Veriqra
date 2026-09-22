package io.github.lz007001cn.veriqra.integration;

import io.github.lz007001cn.veriqra.dao.jdbc.*;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.Connection;

/** Data builders only; uses the same isolated schema and cleanup as Round 1. */
abstract class AssetFixture extends MysqlFixture {
    protected record Parents(Long userId, Long projectId) { }
    protected Parents parents(Connection c) {
        var u = new JdbcUserDao(c).insert(user("asset-owner"));
        var p = new JdbcProjectDao(c).insert(project("ASSET", u.id()));
        return new Parents(u.id(), p.id());
    }
    protected Requirement requirement(Parents p, long key) {
        return new Requirement(null, p.projectId(), key, "需求 ' ? 中文", null, Priority.MEDIUM,
                RequirementStatus.DRAFT, p.userId(), null, null, null);
    }
    protected TestCase testCase(Parents p, long key) {
        return new TestCase(null, p.projectId(), key, "用例 ' ? 中文", null, null, Priority.MEDIUM,
                TestCaseStatus.DRAFT, p.userId(), null, null, null);
    }
    protected TestPlan testPlan(Parents p, long key) {
        return new TestPlan(null, p.projectId(), key, "计划 ' ? 中文", null,
                TestPlanStatus.DRAFT, p.userId(), null, null, null);
    }
}
