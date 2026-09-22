package io.github.lz007001cn.veriqra.web;

import io.github.lz007001cn.veriqra.service.*;
import jakarta.servlet.ServletContext;
import java.util.Objects;

/** Application composition supplies Service interfaces only to the HTTP layer. */
public record WebServices(AuthService auth, ProjectService projects, RequirementService requirements,
                          TestCaseService testCases, TraceabilityService traceability, TestPlanService testPlans,
                          TestRunService testRuns, TestExecutionService execution, DefectService defects) {
    public static final String ATTRIBUTE = WebServices.class.getName();
    public WebServices {
        Objects.requireNonNull(auth); Objects.requireNonNull(projects); Objects.requireNonNull(requirements);
        Objects.requireNonNull(testRuns); Objects.requireNonNull(execution); Objects.requireNonNull(defects);
        Objects.requireNonNull(testCases); Objects.requireNonNull(traceability); Objects.requireNonNull(testPlans);
    }
    public static WebServices from(ServletContext context) {
        return Objects.requireNonNull((WebServices) context.getAttribute(ATTRIBUTE), "Services not initialized");
    }
}
