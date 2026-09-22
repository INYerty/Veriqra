package io.github.lz007001cn.veriqra.web.dto;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.auth.AuthenticatedUser;

public record UserResponse(Long id, String username, SystemRole systemRole, UserStatus status) {
    public static UserResponse from(AuthenticatedUser user) {
        return new UserResponse(user.id(), user.username(), user.systemRole(), user.status());
    }
}
