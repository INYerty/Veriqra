package io.github.lz007001cn.veriqra.web.security;

import jakarta.servlet.ServletContext;

public final class SessionCookiePolicy {
    private SessionCookiePolicy() { }
    public static void configure(ServletContext context, String secure, String publicOrigin) {
        if (secure != null && !secure.equals("true") && !secure.equals("false"))
            throw new IllegalStateException("VERIQRA_SESSION_SECURE must be true or false");
        new SameOriginPolicy(publicOrigin); // Validate before database initialization.
        if (publicOrigin != null && !"true".equals(secure))
            throw new IllegalStateException("A public origin requires VERIQRA_SESSION_SECURE=true");
        var cookie = context.getSessionCookieConfig();
        cookie.setHttpOnly(true);
        cookie.setSecure(Boolean.parseBoolean(secure));
        cookie.setAttribute("SameSite", "Lax");
        cookie.setPath(context.getContextPath().isEmpty() ? "/" : context.getContextPath());
        context.setSessionTimeout(30);
    }
}
