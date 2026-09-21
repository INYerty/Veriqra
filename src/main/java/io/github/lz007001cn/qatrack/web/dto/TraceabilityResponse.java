package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.TraceabilityStatus;
import io.github.lz007001cn.qatrack.service.TraceabilityDetails;
public record TraceabilityResponse(Long requirementId, TestCaseResponse testCase, TraceabilityStatus status) {
    public static TraceabilityResponse from(TraceabilityDetails v) {
        return new TraceabilityResponse(v.link().requirementId(), TestCaseResponse.from(v.testCase()), v.link().status());
    }
}
