package io.github.lz007001cn.veriqra.model;

/** Current persisted row; no business behavior. Generated fields come from MySQL. */
public record TestStep(
        Long testCaseId,
        Integer stepOrder,
        String action,
        String expectedResult) { }
