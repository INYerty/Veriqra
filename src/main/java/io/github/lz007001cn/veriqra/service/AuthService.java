package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.service.auth.AuthenticatedUser;

public interface AuthService {
    AuthenticatedUser authenticate(String username, String password);
    /** Reloads current user state; no cached role or status is trusted. */
    AuthenticatedUser current(Long actorUserId);
}
