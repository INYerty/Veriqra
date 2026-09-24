package io.github.lz007001cn.veriqra.admin;

/** Stable client-visible code with a fixed safe message; never includes SQL or credentials. */
public final class AdminConflictException extends RuntimeException {
    private final String code;
    public AdminConflictException(String code,String message){super(message);this.code=code;}
    public String code(){return code;}
}
