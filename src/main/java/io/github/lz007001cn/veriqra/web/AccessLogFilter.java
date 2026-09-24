package io.github.lz007001cn.veriqra.web;

import io.github.lz007001cn.veriqra.admin.AdminServices;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;

/** Outermost filter: server-generated ID, final response status, canonical remote address, path only. */
public final class AccessLogFilter implements Filter {
    public static final String REQUEST_ID=AccessLogFilter.class.getName()+".requestId";
    @Override public void doFilter(ServletRequest input,ServletResponse output,FilterChain chain)
            throws IOException,ServletException {
        HttpServletRequest request=(HttpServletRequest)input;
        HttpServletResponse response=(HttpServletResponse)output;
        String id=UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID,id);
        response.setHeader("X-Request-ID",id);
        long start=System.nanoTime();
        var observed=new HttpServletResponseWrapper(response) {
            private int status=200;
            @Override public void setStatus(int value){status=value;super.setStatus(value);}
            @Override public void sendError(int value)throws IOException{status=value;super.sendError(value);}
            @Override public void sendError(int value,String message)throws IOException{status=value;super.sendError(value,message);}
            @Override public void sendRedirect(String location)throws IOException{status=302;super.sendRedirect(location);}
            @Override public int getStatus(){return status;}
        };
        try { chain.doFilter(input,observed); }
        catch(IOException | ServletException | RuntimeException | Error failure) {
            // The container sends a 500 after this filter unwinds; record it before telemetry runs.
            if(!observed.isCommitted()) observed.setStatus(500);
            throw failure;
        }
        finally {
            try {
                Object installed=request.getServletContext().getAttribute(AdminServices.ATTRIBUTE);
                if(installed instanceof AdminServices admin) {
                    long elapsed=(System.nanoTime()-start)/1_000_000;
                    int duration=(int)Math.min(Integer.MAX_VALUE,Math.max(0,elapsed));
                    admin.telemetry().access(SessionIdentity.optional(request),request.getRemoteAddr(),
                            request.getHeader("User-Agent"),request.getMethod(),request.getRequestURI(),
                            observed.getStatus(),id,duration);
                }
            } catch(RuntimeException ignored) {
                request.getServletContext().log("Access telemetry unavailable");
            }
        }
    }
}
