package io.github.lz007001cn.veriqra.web.dto;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.importing.*;

public record JUnitResultResponse(int entryIndex, AutomationSource source, String namespace, String externalKey,
                                  TestAttemptStatus outcome, Long durationMs, String comment,
                                  String failureMessage) {
    public static JUnitResultResponse from(JUnitTestResult value) {
        return new JUnitResultResponse(value.entryIndex(), value.identity().source(), value.identity().namespace(),
                value.identity().externalKey(), value.status(), value.durationMs(), value.comment(),
                value.failureMessage());
    }
}
