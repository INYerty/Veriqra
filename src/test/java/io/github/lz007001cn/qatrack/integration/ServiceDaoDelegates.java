package io.github.lz007001cn.qatrack.integration;

import io.github.lz007001cn.qatrack.dao.*;
import io.github.lz007001cn.qatrack.model.*;
import io.github.lz007001cn.qatrack.service.support.ServiceDaos;
import java.time.LocalDateTime;
import java.util.*;

final class ServiceDaoDelegates {
    private ServiceDaoDelegates() { }
    static ServiceDaos requirement(ServiceDaos d, RequirementDao replacement) {
        return new ServiceDaos(d.users(), d.projects(), d.members(), d.counters(), replacement,
                d.testCases(), d.steps(), d.traceability(), d.testRuns());
    }
    static ServiceDaos steps(ServiceDaos d, TestStepDao replacement) {
        return new ServiceDaos(d.users(), d.projects(), d.members(), d.counters(), d.requirements(),
                d.testCases(), replacement, d.traceability(), d.testRuns());
    }
    static ServiceDaos traceability(ServiceDaos d, TestCaseRequirementDao replacement) {
        return new ServiceDaos(d.users(), d.projects(), d.members(), d.counters(), d.requirements(),
                d.testCases(), d.steps(), replacement, d.testRuns());
    }

    abstract static class RequirementDelegate implements RequirementDao {
        final RequirementDao target;
        RequirementDelegate(RequirementDao target) { this.target = target; }
        public Optional<Requirement> findById(Long id) { return target.findById(id); }
        public Optional<Requirement> findByIdForUpdate(Long id) { return target.findByIdForUpdate(id); }
        public Optional<Requirement> findByKey(Long projectId, Long keyNo) { return target.findByKey(projectId, keyNo); }
        public List<Requirement> listByProject(Long projectId) { return target.listByProject(projectId); }
        public Requirement insert(Requirement value) { return target.insert(value); }
        public Requirement update(Requirement value) { return target.update(value); }
    }
    abstract static class StepDelegate implements TestStepDao {
        final TestStepDao target;
        StepDelegate(TestStepDao target) { this.target = target; }
        public TestStep add(TestStep value) { return target.add(value); }
        public Optional<TestStep> find(Long caseId, Integer order) { return target.find(caseId, order); }
        public List<TestStep> listByTestCase(Long caseId) { return target.listByTestCase(caseId); }
        public boolean updateContent(TestStep value) { return target.updateContent(value); }
        public boolean remove(Long caseId, Integer order) { return target.remove(caseId, order); }
        public int deleteByTestCase(Long caseId) { return target.deleteByTestCase(caseId); }
    }
    abstract static class TraceabilityDelegate implements TestCaseRequirementDao {
        final TestCaseRequirementDao target;
        TraceabilityDelegate(TestCaseRequirementDao target) { this.target = target; }
        public TestCaseRequirement add(TestCaseRequirement value) { return target.add(value); }
        public Optional<TestCaseRequirement> find(Long r, Long c) { return target.find(r, c); }
        public boolean existsRecord(Long r, Long c) { return target.existsRecord(r, c); }
        public List<TestCaseRequirement> listByRequirement(Long r) { return target.listByRequirement(r); }
        public List<TestCaseRequirement> listByTestCase(Long c) { return target.listByTestCase(c); }
        public boolean updateReviewState(Long r, Long c, TraceabilityStatus s, Long by, LocalDateTime at) {
            return target.updateReviewState(r, c, s, by, at);
        }
        public boolean markRemoved(Long r, Long c) { return target.markRemoved(r, c); }
        public int markConfirmedNeedsReviewByRequirement(Long r) { return target.markConfirmedNeedsReviewByRequirement(r); }
        public int markConfirmedNeedsReviewByTestCase(Long c) { return target.markConfirmedNeedsReviewByTestCase(c); }
    }
}
