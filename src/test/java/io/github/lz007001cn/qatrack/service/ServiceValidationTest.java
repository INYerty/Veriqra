package io.github.lz007001cn.qatrack.service;

import io.github.lz007001cn.qatrack.service.command.TestStepInput;
import io.github.lz007001cn.qatrack.service.exception.ValidationException;
import io.github.lz007001cn.qatrack.service.support.ServiceValidation;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ServiceValidationTest {
    @Test void requiredTextUsesSchemaCharacterLengthIncludingUnicodeCodePoints() {
        assertEquals("标题", ServiceValidation.requiredText("标题", 2, "title"));
        assertEquals("😀", ServiceValidation.requiredText("😀", 1, "title"));
        assertThrows(ValidationException.class, () -> ServiceValidation.requiredText("😀a", 1, "title"));
        assertThrows(ValidationException.class, () -> ServiceValidation.requiredText("  ", 240, "title"));
    }

    @Test void stepsMustBeExplicitContiguousAndNonblank() {
        var valid = List.of(new TestStepInput(1, "A", "EA"), new TestStepInput(2, "B", "EB"));
        assertEquals(valid, ServiceValidation.steps(valid));
        assertEquals(List.of(), ServiceValidation.steps(List.of()));
        assertThrows(ValidationException.class, () -> ServiceValidation.steps(null));
        assertThrows(ValidationException.class, () -> ServiceValidation.steps(List.of(new TestStepInput(2, "A", "EA"))));
        assertThrows(ValidationException.class, () -> ServiceValidation.steps(List.of(new TestStepInput(1, " ", "EA"))));
    }
}
