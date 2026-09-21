package io.github.lz007001cn.qatrack.web.dto;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.query.RunDetails;
import java.time.LocalDateTime;
import java.util.List;
public record RunDetailResponse(RunResponse run, List<CaseResponse> cases) {
    public static RunDetailResponse from(RunDetails v) {
        return new RunDetailResponse(RunResponse.from(v.run()), v.cases().stream().map(CaseResponse::from).toList());
    }
    public record CaseResponse(Long runCaseId, Long testCaseId, String snapshotTitle, String snapshotDescription,
            String snapshotPreconditions, Priority snapshotPriority, LocalDateTime capturedAt,
            List<StepResponse> steps, String currentOutcome, AttemptResponse latestAttempt) {
        static CaseResponse from(RunDetails.CaseDetails v) {
            var s = v.snapshot();
            return new CaseResponse(s.id(), s.testCaseId(), s.snapshotTitle(), s.snapshotDescription(),
                    s.snapshotPreconditions(), s.snapshotPriority(), s.capturedAt(),
                    v.steps().stream().map(step -> new StepResponse(step.stepOrder(), step.action(), step.expectedResult())).toList(),
                    v.latest().map(a -> a.status().name()).orElse("NOT_RUN"),
                    v.latest().map(AttemptResponse::from).orElse(null));
        }
    }
    public record StepResponse(Integer stepOrder, String action, String expectedResult) { }
}
