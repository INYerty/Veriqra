package io.github.lz007001cn.veriqra.web;

import io.github.lz007001cn.veriqra.service.exception.AuthenticationException;
import jakarta.servlet.http.*;

public final class SessionIdentity {
    private static final String ACTOR_ID = SessionIdentity.class.getName() + ".actorUserId";
    private SessionIdentity() { }

    public static Long require(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) throw new AuthenticationException();
        try {
            Object value = session.getAttribute(ACTOR_ID);
            if (value instanceof Long id && id > 0) return id;
        } catch (IllegalStateException ignored) { /* Concurrent logout: fail closed. */ }
        throw new AuthenticationException();
    }

    public static void login(HttpServletRequest request, Long id) {
        // Invalidate any pre-login session so fixation IDs and arbitrary old attributes do not survive.
        logout(request);
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(1800);
        session.setAttribute(ACTOR_ID, id);
    }

    public static void logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try { session.invalidate(); } catch (IllegalStateException ignored) { /* Already invalidated. */ }
        }
    }
}
