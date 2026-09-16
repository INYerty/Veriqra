package io.github.lz007001cn.qatrack.service.support;

import java.sql.Connection;

@FunctionalInterface
public interface ServiceDaoFactory {
    ServiceDaos create(Connection connection);
}
