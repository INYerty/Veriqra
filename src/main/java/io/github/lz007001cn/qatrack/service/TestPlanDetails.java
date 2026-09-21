package io.github.lz007001cn.qatrack.service;
import io.github.lz007001cn.qatrack.model.*;
import java.util.List;
/** Parent and children read in one transaction snapshot. */
public record TestPlanDetails(TestPlan plan, List<TestPlanCase> cases) {
    public TestPlanDetails { cases = List.copyOf(cases); }
}
