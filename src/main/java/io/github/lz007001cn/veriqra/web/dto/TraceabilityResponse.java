package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.TraceabilityStatus;
import io.github.lz007001cn.veriqra.service.TraceabilityDetails;
public record TraceabilityResponse(Long requirementId, TestCaseResponse testCase, TraceabilityStatus status) {
    public static TraceabilityResponse from(TraceabilityDetails v) {
        return new TraceabilityResponse(v.link().requirementId(), TestCaseResponse.from(v.testCase()), v.link().status());
    }
}
