package io.github.lz007001cn.qatrack.service.support;

import io.github.lz007001cn.qatrack.exception.OptimisticLockException;
import io.github.lz007001cn.qatrack.service.exception.ConflictException;

public final class ServiceFailures {
    private ServiceFailures() { }
    public static ConflictException stale(String entity, OptimisticLockException cause) {
        return new ConflictException(entity + " was changed or removed", cause);
    }
}
