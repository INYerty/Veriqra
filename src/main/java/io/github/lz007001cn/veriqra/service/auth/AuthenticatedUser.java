package io.github.lz007001cn.veriqra.service.auth;

import io.github.lz007001cn.veriqra.model.*;

public record AuthenticatedUser(Long id, String username, SystemRole systemRole, UserStatus status) {
    public static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.id(), user.username(), user.systemRole(), user.status());
    }
}
