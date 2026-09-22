package io.github.lz007001cn.veriqra.web.dto;

public record LoginRequest(String username, String password) {
    @Override public String toString() { return "LoginRequest[redacted]"; }
}
