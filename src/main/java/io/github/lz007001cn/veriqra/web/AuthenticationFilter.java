package io.github.lz007001cn.veriqra.web;

import io.github.lz007001cn.veriqra.service.exception.AuthenticationException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

public final class AuthenticationFilter implements Filter {
    private final io.github.lz007001cn.veriqra.web.security.SameOriginPolicy origins;
    public AuthenticationFilter() { this(io.github.lz007001cn.veriqra.config.EnvironmentVariables.get("PUBLIC_ORIGIN")); }
    public AuthenticationFilter(String publicOrigin) {
        origins = new io.github.lz007001cn.veriqra.web.security.SameOriginPolicy(publicOrigin);
    }
    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) input;
        origins.check(request);
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
        chain.doFilter(input, output);
    }
}
