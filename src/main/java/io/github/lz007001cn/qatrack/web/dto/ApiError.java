package io.github.lz007001cn.qatrack.web.dto;

public record ApiError(Error error) {
    public record Error(String code, String message) { }
    public static ApiError of(String code, String message) { return new ApiError(new Error(code, message)); }
}
