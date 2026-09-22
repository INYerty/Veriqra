package io.github.lz007001cn.veriqra.web.dto;
import io.github.lz007001cn.veriqra.model.TestAttemptStatus;
import io.github.lz007001cn.veriqra.service.query.DefectDetails;
import java.time.LocalDateTime;
import java.util.List;
public record DefectDetailResponse(DefectResponse defect, List<EvidenceResponse> evidence) {
    public static DefectDetailResponse from(DefectDetails v) {
        return new DefectDetailResponse(DefectResponse.from(v.defect()), v.evidence().stream()
                .map(e -> new EvidenceResponse(e.attempt().id(), e.runId(), e.attempt().testRunCaseId(),
                        e.attempt().attemptNo(), e.attempt().status(), e.attempt().failureMessage(),
                        e.link().linkedBy(), e.link().linkedAt())).toList());
    }
    public record EvidenceResponse(Long attemptId, Long runId, Long runCaseId, Integer attemptNo,
            TestAttemptStatus outcome, String failureMessage, Long linkedBy, LocalDateTime linkedAt) { }
}
