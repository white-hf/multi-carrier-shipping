package com.enterprise.shipping.shared.infrastructure.persistence;

import java.sql.SQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/**
 * RDBMS-agnostic uniqueness violation detector across standard ANSI SQLStates (23xxx),
 * Spring DuplicateKeyException translations, and SQLite's extended constraint codes.
 *
 * Deliberately excludes CHECK, NOT NULL, FOREIGN KEY, and lock/busy errors so only
 * genuine unique-constraint races are treated as idempotent conflicts.
 */
public final class UniqueConstraints {

    private UniqueConstraints() {
    }

    public static boolean isUniqueViolation(DataAccessException error) {
        if (error instanceof DuplicateKeyException) {
            return true;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlEx) {
                String msg = sqlEx.getMessage();
                if (msg != null
                        && (msg.contains("SQLITE_CONSTRAINT_UNIQUE")
                                || msg.contains("SQLITE_CONSTRAINT_PRIMARYKEY")
                                || msg.contains("UNIQUE constraint failed"))) {
                    return true;
                }
                String state = sqlEx.getSQLState();
                if ("23505".equals(state)) {
                    return true;
                }
            }
        }
        return false;
    }
}
