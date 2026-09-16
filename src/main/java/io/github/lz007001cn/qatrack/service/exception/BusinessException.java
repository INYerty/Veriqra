package io.github.lz007001cn.qatrack.service.exception;

/** Base class for caller-correctable business failures; independent of HTTP. */
public class BusinessException extends RuntimeException {
    public BusinessException(String message) { super(message); }
    public BusinessException(String message, Throwable cause) { super(message, cause); }
}
