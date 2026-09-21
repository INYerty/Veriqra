package io.github.lz007001cn.qatrack.web;

import jakarta.servlet.*;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.*;
import java.nio.file.*;
import java.util.Set;

/** Real loopback Tomcat; no database is opened by this component-test composition root. */
public final class EmbeddedWebServer implements AutoCloseable {
    private final Tomcat tomcat;
    public EmbeddedWebServer(Path directory, WebServices services) throws Exception {
        this(directory, services, false);
    }
    public EmbeddedWebServer(Path directory, WebServices services, boolean secureCookie) throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(directory.toString());
        tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Context context = tomcat.addContext("/qatrack", directory.toString());
        context.setParentClassLoader(EmbeddedWebServer.class.getClassLoader());
        context.addServletContainerInitializer((classes, servletContext) -> {
            servletContext.setAttribute(WebServices.ATTRIBUTE, services);
            servletContext.setSessionTrackingModes(Set.of(SessionTrackingMode.COOKIE));
            io.github.lz007001cn.qatrack.web.security.SessionCookiePolicy.configure(
                    servletContext, Boolean.toString(secureCookie), null);
        }, Set.of());
        Tomcat.addServlet(context, "api", new ApiServlet());
        context.addServletMappingDecoded("/api/*", "api");
        filter(context, "errors", new ApiExceptionFilter());
        filter(context, "auth", new AuthenticationFilter());
        tomcat.start();
    }
    private static void filter(Context context, String name, Filter filter) {
        FilterDef def = new FilterDef(); def.setFilterName(name); def.setFilter(filter);
        context.addFilterDef(def);
        FilterMap map = new FilterMap(); map.setFilterName(name); map.addURLPattern("/api/*");
        context.addFilterMap(map);
    }
    public String base() { return "http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/qatrack"; }
    @Override public void close() throws Exception { try { tomcat.stop(); } finally { tomcat.destroy(); } }
}
