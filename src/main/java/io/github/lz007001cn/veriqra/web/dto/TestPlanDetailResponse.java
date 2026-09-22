package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.service.TestPlanDetails;
import java.util.List;
public record TestPlanDetailResponse(TestPlanResponse plan, List<Long> testCaseIds) {
    public static TestPlanDetailResponse from(TestPlanDetails v) {
        return new TestPlanDetailResponse(TestPlanResponse.from(v.plan()),
                v.cases().stream().map(c -> c.testCaseId()).toList());
    }
}
