package io.github.lz007001cn.veriqra.service.query;
import io.github.lz007001cn.veriqra.model.*;
import java.util.*;
/** A single Service transaction supplies the run and all snapshot/outcome reads. */
public record RunDetails(TestRun run, List<CaseDetails> cases) {
    public RunDetails { cases = List.copyOf(cases); }
    public record CaseDetails(TestRunCase snapshot, List<TestRunCaseStep> steps, Optional<TestAttempt> latest) {
        public CaseDetails { steps = List.copyOf(steps); Objects.requireNonNull(latest); }
    }
}
