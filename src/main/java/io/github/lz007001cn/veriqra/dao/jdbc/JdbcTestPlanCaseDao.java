package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.TestPlanCaseDao;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.model.TestPlanCase;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** No sort_order/status/version columns are invented for this association. */
public final class JdbcTestPlanCaseDao implements TestPlanCaseDao {
    private static final String SELECT = "SELECT pc.test_plan_id, pc.test_case_id, pc.added_by, pc.added_at FROM test_plan_cases pc";
    private final Connection connection;
    public JdbcTestPlanCaseDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @Override public TestPlanCase add(TestPlanCase value) {
        try (PreparedStatement s = connection.prepareStatement(
                "INSERT INTO test_plan_cases (test_plan_id, test_case_id, added_by) VALUES (?,?,?)")) {
            s.setObject(1, value.testPlanId(), Types.BIGINT);
            s.setObject(2, value.testCaseId(), Types.BIGINT);
            s.setObject(3, value.addedBy(), Types.BIGINT);
            if (s.executeUpdate() != 1) throw new SQLException("Unexpected plan association insert count");
            return find(value.testPlanId(), value.testCaseId()).orElseThrow(() -> new DataAccessException("Added plan case could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Add TestPlanCase", e); }
    }

    @Override public Optional<TestPlanCase> find(Long testPlanId, Long testCaseId) {
        return query(SELECT + " WHERE pc.test_plan_id=? AND pc.test_case_id=?", testPlanId, testCaseId).stream().findFirst();
    }
    @Override public boolean exists(Long testPlanId, Long testCaseId) { return find(testPlanId, testCaseId).isPresent(); }
    @Override public List<TestPlanCase> listByTestPlan(Long testPlanId) {
        return query(SELECT + " JOIN test_cases c ON c.id=pc.test_case_id WHERE pc.test_plan_id=? ORDER BY c.key_no, c.id", testPlanId);
    }
    @Override public List<TestPlanCase> listByTestPlanForUpdate(Long testPlanId) {
        requireTransaction();
        return query(SELECT + " WHERE pc.test_plan_id=? ORDER BY pc.test_case_id FOR UPDATE", testPlanId);
    }
    @Override public List<TestPlanCase> listByTestCase(Long testCaseId) {
        return query(SELECT + " WHERE pc.test_case_id=? ORDER BY pc.test_plan_id", testCaseId);
    }
    private List<TestPlanCase> query(String sql, Long... values) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i], Types.BIGINT);
            try (ResultSet rs = s.executeQuery()) {
                List<TestPlanCase> rows = new ArrayList<>();
                while (rs.next()) rows.add(new TestPlanCase(rs.getObject("test_plan_id", Long.class),
                        rs.getObject("test_case_id", Long.class), rs.getObject("added_by", Long.class), rs.getObject("added_at", LocalDateTime.class)));
                return List.copyOf(rows);
            }
        } catch (SQLException e) { throw new DataAccessException("Find TestPlanCase", e); }
    }
    private void requireTransaction() {
        try {
            if (connection.getAutoCommit()) throw new SQLException("Plan scope locking requires an outer transaction", "25000");
        } catch (SQLException e) { throw new DataAccessException("Lock TestPlanCase", e); }
    }
    @Override public boolean remove(Long testPlanId, Long testCaseId) {
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM test_plan_cases WHERE test_plan_id=? AND test_case_id=?")) {
            s.setObject(1, testPlanId, Types.BIGINT);
            s.setObject(2, testCaseId, Types.BIGINT);
            return s.executeUpdate() > 0;
        } catch (SQLException e) { throw new DataAccessException("Remove TestPlanCase", e); }
    }
}
