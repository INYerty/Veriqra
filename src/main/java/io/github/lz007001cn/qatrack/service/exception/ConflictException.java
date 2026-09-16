package io.github.lz007001cn.qatrack.service.exception;

public final class ConflictException extends BusinessException {
    public ConflictException(String message) { super(message); }
    public ConflictException(String message, Throwable cause) { super(message, cause); }
}
