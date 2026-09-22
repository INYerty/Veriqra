package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.exception.DataAccessException;
import java.sql.ResultSet;
import java.sql.SQLException;

/** Shared checked conversions for the selected Java numeric mapping. */
final class JdbcValues {
    private JdbcValues() { }
    static int version(ResultSet rs) throws SQLException {
        long value = rs.getLong("lock_version");
        if (value < 0 || value > Integer.MAX_VALUE) throw new SQLException("lock_version exceeds Java Integer range", "22003");
        return (int) value;
    }
    static void writableVersion(Integer value) {
        if (value == null || value < 0 || value == Integer.MAX_VALUE) {
            throw new DataAccessException("lockVersion must be within [0, Integer.MAX_VALUE) for update");
        }
    }
}
