package io.github.lz007001cn.qatrack.service.exception;

/** Authentication failure, independent of HTTP; deliberately does not identify failed credential. */
public final class AuthenticationException extends BusinessException {
    public AuthenticationException() { super("Authentication required or credentials invalid"); }
}
