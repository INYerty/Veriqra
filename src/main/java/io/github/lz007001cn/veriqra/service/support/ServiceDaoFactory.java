package io.github.lz007001cn.veriqra.service.support;

import java.sql.Connection;

@FunctionalInterface
public interface ServiceDaoFactory {
    ServiceDaos create(Connection connection);
}
