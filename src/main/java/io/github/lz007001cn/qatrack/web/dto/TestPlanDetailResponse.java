package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.service.TestPlanDetails;
import java.util.List;
public record TestPlanDetailResponse(TestPlanResponse plan, List<Long> testCaseIds) {
    public static TestPlanDetailResponse from(TestPlanDetails v) {
        return new TestPlanDetailResponse(TestPlanResponse.from(v.plan()),
                v.cases().stream().map(c -> c.testCaseId()).toList());
    }
}
