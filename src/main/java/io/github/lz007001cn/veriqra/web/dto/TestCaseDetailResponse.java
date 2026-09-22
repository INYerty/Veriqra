package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.service.TestCaseDetails;
import java.util.List;
public record TestCaseDetailResponse(TestCaseResponse testCase, List<TestStepResponse> steps) {
    public static TestCaseDetailResponse from(TestCaseDetails v) {
        return new TestCaseDetailResponse(TestCaseResponse.from(v.testCase()),
                v.steps().stream().map(s -> new TestStepResponse(s.stepOrder(), s.action(), s.expectedResult())).toList());
    }
    public record TestStepResponse(Integer stepOrder, String action, String expectedResult) { }
}
