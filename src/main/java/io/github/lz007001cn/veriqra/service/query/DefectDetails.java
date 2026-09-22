package io.github.lz007001cn.veriqra.service.query;
import io.github.lz007001cn.veriqra.model.*;
import java.util.List;
/** Evidence context is read with the defect in one transaction. */
public record DefectDetails(Defect defect, List<Evidence> evidence) {
    public DefectDetails { evidence = List.copyOf(evidence); }
    public record Evidence(TestAttemptDefect link, TestAttempt attempt, Long runId) { }
}
