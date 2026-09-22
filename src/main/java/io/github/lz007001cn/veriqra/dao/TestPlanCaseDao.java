package io.github.lz007001cn.veriqra.dao;

import io.github.lz007001cn.veriqra.model.TestPlanCase;
import java.util.*;

/** Current plan scope; caller checks project/parent status and coordinates parent updates. */
public interface TestPlanCaseDao {
    TestPlanCase add(TestPlanCase value);
    Optional<TestPlanCase> find(Long testPlanId, Long testCaseId);
    boolean exists(Long testPlanId, Long testCaseId);
    /** Frozen presentation order: case key_no, with case ID as stable tie breaker. */
    List<TestPlanCase> listByTestPlan(Long testPlanId);
    /** Current locking read ordered by case ID; caller locks TestCases before Plan, then uses this only to revalidate scope. */
    List<TestPlanCase> listByTestPlanForUpdate(Long testPlanId);
    List<TestPlanCase> listByTestCase(Long testCaseId);
    /** Physical removal of the association only. */
    boolean remove(Long testPlanId, Long testCaseId);
}
