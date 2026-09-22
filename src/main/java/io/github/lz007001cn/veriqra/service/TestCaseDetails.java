package io.github.lz007001cn.veriqra.service;
import io.github.lz007001cn.veriqra.model.*;
import java.util.List;
/** Parent and children read in one transaction snapshot. */
public record TestCaseDetails(TestCase testCase, List<TestStep> steps) {
    public TestCaseDetails { steps = List.copyOf(steps); }
}
