package io.github.lz007001cn.veriqra.bootstrap;

import io.github.lz007001cn.veriqra.config.DatabaseConfig;
import io.github.lz007001cn.veriqra.jdbc.*;
import io.github.lz007001cn.veriqra.service.*;
import io.github.lz007001cn.veriqra.service.auth.PasswordVerifier;
import io.github.lz007001cn.veriqra.service.support.*;
import io.github.lz007001cn.veriqra.web.WebServices;
import jakarta.servlet.*;
import java.sql.*;
import java.util.*;

/** Composition root only: owns the application pool, never handles an HTTP request. */
public final class ApplicationListener implements ServletContextListener {
    private ConnectionPool pool;
    private final List<Driver> ownedDrivers = new ArrayList<>();
    @Override public void contextInitialized(ServletContextEvent event) {
        var context = event.getServletContext();
        io.github.lz007001cn.veriqra.web.security.SessionCookiePolicy.configure(context,
                io.github.lz007001cn.veriqra.config.EnvironmentVariables.get("SESSION_SECURE"), io.github.lz007001cn.veriqra.config.EnvironmentVariables.get("PUBLIC_ORIGIN"));
        try {
            // Tomcat may initialize DriverManager before WEB-INF/lib is visible to its classloader.
            List<Driver> existing;
            Thread thread = Thread.currentThread();
            ClassLoader previousLoader = thread.getContextClassLoader();
            try {
                // Initialize DriverManager against the container parent, not this app's SPI resources.
                thread.setContextClassLoader(getClass().getClassLoader().getParent());
                existing = DriverManager.drivers().toList();
            } finally { thread.setContextClassLoader(previousLoader); }
            Class.forName("com.mysql.cj.jdbc.Driver");
            DriverManager.drivers().filter(driver -> !existing.contains(driver)
                    && driver.getClass().getClassLoader() == getClass().getClassLoader()).forEach(ownedDrivers::add);
            pool = new ConnectionPool(DatabaseConfig.load());
            var transactions = new JdbcServiceTransaction(new JdbcTransactionManager(pool));
            var daos = new JdbcServiceDaoFactory();
            var access = new ProjectAccessPolicy();
            context.setAttribute(WebServices.ATTRIBUTE, new WebServices(
                    new DefaultAuthService(transactions, daos, new PasswordVerifier()),
                    new DefaultProjectService(transactions, daos, access),
                    new DefaultRequirementService(transactions, daos, access),
                    new DefaultTestCaseService(transactions, daos, access),
                    new DefaultTraceabilityService(transactions, daos, access, java.time.Clock.systemUTC()),
                    new DefaultTestPlanService(transactions, daos, access),
                    new DefaultTestRunService(transactions, daos, access, java.time.Clock.systemUTC()),
                    new DefaultTestExecutionService(transactions, daos, access, java.time.Clock.systemUTC()),
                    new DefaultDefectService(transactions, daos, access)));
        } catch (SQLException | ClassNotFoundException | RuntimeException e) {
            closePool(context);
            // Configuration/driver exceptions may contain credentials; do not attach them to container logs.
            throw new IllegalStateException("Database initialization failed; check external configuration and availability");
        }
    }
    @Override public void contextDestroyed(ServletContextEvent event) {
        event.getServletContext().removeAttribute(WebServices.ATTRIBUTE);
        closePool(event.getServletContext());
    }
    private void closePool(ServletContext context) {
        if (pool != null) {
            try { pool.close(); } catch (SQLException e) { context.log("Connection pool cleanup failed"); }
            pool = null;
        }
        if (!ownedDrivers.isEmpty()) {
            // Connector/J is a runtime dependency. Stop only a driver initialized by this application.
            try {
                Class.forName("com.mysql.cj.jdbc.AbandonedConnectionCleanupThread")
                        .getMethod("checkedShutdown").invoke(null);
            } catch (ReflectiveOperationException e) { context.log("JDBC cleanup thread shutdown failed"); }
            for (Driver driver : ownedDrivers) {
                try { DriverManager.deregisterDriver(driver); }
                catch (SQLException e) { context.log("JDBC driver deregistration failed"); }
            }
            ownedDrivers.clear();
        }
    }
}
