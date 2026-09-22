package io.github.lz007001cn.veriqra.service.support;

import io.github.lz007001cn.veriqra.service.command.TestStepInput;
import io.github.lz007001cn.veriqra.service.exception.ValidationException;
import java.util.List;

public final class ServiceValidation {
    private ServiceValidation() { }
    public static <T> T required(T value, String field) {
        if (value == null) throw new ValidationException(field + " is required");
        return value;
    }
    public static String requiredText(String value, int max, String field) {
        if (value == null || value.isBlank()) throw new ValidationException(field + " is required");
        if (value.codePointCount(0, value.length()) > max) throw new ValidationException(field + " exceeds " + max + " characters");
        return value;
    }
    public static String optionalText(String value, int max, String field) {
        if (value != null && value.codePointCount(0, value.length()) > max) {
            throw new ValidationException(field + " exceeds " + max + " characters");
        }
        return value;
    }
    public static List<TestStepInput> steps(List<TestStepInput> values) {
        if (values == null) throw new ValidationException("steps is required; use an empty list for a draft without steps");
        for (int i = 0; i < values.size(); i++) {
            TestStepInput step = required(values.get(i), "step");
            if (step.stepOrder() == null || step.stepOrder() != i + 1) {
                throw new ValidationException("stepOrder must be contiguous from 1");
            }
            if (step.action() == null || step.action().isBlank()) throw new ValidationException("step action is required");
            if (step.expectedResult() == null || step.expectedResult().isBlank()) {
                throw new ValidationException("step expectedResult is required");
            }
        }
        return List.copyOf(values);
    }
}
