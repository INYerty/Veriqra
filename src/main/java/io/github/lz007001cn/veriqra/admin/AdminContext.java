package io.github.lz007001cn.veriqra.admin;

/** Trusted server-side request facts; never built from forwarded headers or client request IDs. */
public record AdminContext(long actorId, String ipAddress, String requestId) { }
