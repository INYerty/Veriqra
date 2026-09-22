package io.github.lz007001cn.veriqra.service.exception;

public final class ValidationException extends BusinessException {
    public ValidationException(String message) { super(message); }
    public ValidationException(String message, Throwable cause) { super(message, cause); }
}
