package io.github.lz007001cn.veriqra.service.support;

import java.sql.Connection;

/** Service-facing transaction boundary, kept small for constructor injection and fast tests. */
public interface ServiceTransaction {
    @FunctionalInterface interface Work<T> { T execute(Connection connection); }
    <T> T execute(Work<T> work);
}
