package io.github.lz007001cn.veriqra.exception;

import java.sql.SQLException;

/** Persistence failure; preserves SQLState/vendor code without copying SQL values into the message. */
public class DataAccessException extends RuntimeException {
    private final String sqlState;
    private final int vendorCode;

    public DataAccessException(String operation, SQLException cause) {
        super(operation + " failed (SQLState=" + cause.getSQLState() + ", code=" + cause.getErrorCode() + ")", cause);
        sqlState = cause.getSQLState();
        vendorCode = cause.getErrorCode();
    }

    public DataAccessException(String message) {
        super(message);
        sqlState = null;
        vendorCode = 0;
    }

    public String getSqlState() { return sqlState; }
    public int getVendorCode() { return vendorCode; }
}
