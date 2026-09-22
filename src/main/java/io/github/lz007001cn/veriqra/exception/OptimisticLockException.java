package io.github.lz007001cn.veriqra.exception;

/** The row no longer exists or the supplied lock version is stale. */
public final class OptimisticLockException extends DataAccessException {
    public OptimisticLockException() { super("Update rejected: missing row or stale lock version"); }
}
