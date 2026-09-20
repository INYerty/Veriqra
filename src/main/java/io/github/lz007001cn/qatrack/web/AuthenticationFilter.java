package io.github.lz007001cn.qatrack.web;

import io.github.lz007001cn.qatrack.service.exception.AuthenticationException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

public final class AuthenticationFilter implements Filter {
    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) input;
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (!path.equals("/api/auth/login")) {
            Long actor = SessionIdentity.require(request);
            try {
                WebServices.from(request.getServletContext()).auth().current(actor);
            } catch (AuthenticationException e) {
                SessionIdentity.logout(request);
                throw e;
            }
        }
        // Same-origin browser clients can set this header; cross-origin scripts need a preflight,
        // which is not granted. This includes login/logout, where JSON alone is insufficient.
        if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")
                && !request.getMethod().equals("OPTIONS")
                && !"1".equals(request.getHeader("X-QATrack-Request"))) {
            throw new HttpFailure(403, "REQUEST_HEADER_REQUIRED", "X-QATrack-Request: 1 is required");
        }
        chain.doFilter(input, output);
    }
}
