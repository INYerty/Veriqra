package io.github.lz007001cn.veriqra.model;

import java.time.LocalDateTime;

/** Immutable row data. DATETIME values follow the application's UTC convention, without a zone field. */
public record User(Long id, String username, String displayName, String passwordHash,
                   SystemRole systemRole, UserStatus status, LocalDateTime createdAt,
                   LocalDateTime updatedAt, Integer lockVersion) {
    @Override public String toString() { return "User[id=" + id + ", status=" + status + "]"; }
}
