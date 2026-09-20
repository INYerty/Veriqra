package io.github.lz007001cn.qatrack.web;

/** HTTP transport failure; deliberately kept outside the Service exception hierarchy. */
public final class HttpFailure extends RuntimeException {
    private final int status;
    private final String code;
    public HttpFailure(int status, String code, String message) {
        super(message); this.status = status; this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
}
