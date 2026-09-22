package io.github.lz007001cn.veriqra.service.support;

import io.github.lz007001cn.veriqra.exception.OptimisticLockException;
import io.github.lz007001cn.veriqra.service.exception.ConflictException;

public final class ServiceFailures {
    private ServiceFailures() { }
    public static ConflictException stale(String entity, OptimisticLockException cause) {
        return new ConflictException(entity + " was changed or removed", cause);
    }
}
