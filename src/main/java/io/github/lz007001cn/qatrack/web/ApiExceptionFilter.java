package io.github.lz007001cn.qatrack.web;

import io.github.lz007001cn.qatrack.service.exception.*;
import io.github.lz007001cn.qatrack.web.dto.ApiError;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;

public final class ApiExceptionFilter implements Filter {
    @Override public void doFilter(ServletRequest input, ServletResponse output, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) input;
        HttpServletResponse response = (HttpServletResponse) output;
        request.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        try {
            chain.doFilter(input, output);
        } catch (Throwable failure) {
            int status = 500;
            String code = "INTERNAL_ERROR";
            String message = "An internal error occurred";
            if (failure instanceof HttpFailure f) { status = f.status(); code = f.code(); message = f.getMessage(); }
            else if (failure instanceof io.github.lz007001cn.qatrack.web.security.LoginRateLimiter.Limited f) {
                status = 429; code = "TOO_MANY_REQUESTS"; message = "Login temporarily unavailable; retry later";
                response.setHeader("Retry-After", Long.toString(f.retryAfter()));
            }
            else if (failure instanceof AuthenticationException) { status = 401; code = "UNAUTHENTICATED"; message = "Authentication required or credentials invalid"; }
            else if (failure instanceof ValidationException) { status = 400; code = "VALIDATION"; message = "Request validation failed"; }
            else if (failure instanceof NotFoundException) { status = 404; code = "NOT_FOUND"; message = "Resource not found"; }
            else if (failure instanceof ForbiddenException) { status = 403; code = "FORBIDDEN"; message = "Operation is not permitted"; }
            else if (failure instanceof ConflictException) { status = 409; code = "CONFLICT"; message = "Request conflicts with current state"; }
            if (status == 500) {
                // Do not log exception messages/causes: a JDBC or JSON message may contain secrets.
                StringBuilder trace = new StringBuilder("Unhandled API failure: ").append(failure.getClass().getName());
                for (StackTraceElement frame : failure.getStackTrace()) trace.append("\n at ").append(frame);
                request.getServletContext().log(trace.toString());
            }
            if (!response.isCommitted()) {
                response.resetBuffer();
                JsonHttp.write(response, status, ApiError.of(code, message));
            }
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
        }
    }
}
