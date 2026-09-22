package io.github.lz007001cn.veriqra.service;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.auth.*;
import io.github.lz007001cn.veriqra.service.exception.AuthenticationException;
import io.github.lz007001cn.veriqra.service.support.*;
import java.util.Objects;

public final class DefaultAuthService implements AuthService {
    private final ServiceTransaction transactions;
    private final ServiceDaoFactory daos;
    private final PasswordVerifier passwords;

    public DefaultAuthService(ServiceTransaction transactions, ServiceDaoFactory daos, PasswordVerifier passwords) {
        this.transactions = Objects.requireNonNull(transactions);
        this.daos = Objects.requireNonNull(daos);
        this.passwords = Objects.requireNonNull(passwords);
    }

    @Override public AuthenticatedUser authenticate(String username, String password) {
        if (username == null || username.isBlank() || username.length() > 64
                || username.chars().anyMatch(ch -> ch < 32 || ch > 126)
                || password == null || password.isEmpty() || password.length() > 1024) {
            throw new AuthenticationException();
        }
        // Do not retain a JDBC lease while performing expensive password derivation.
        User user = transactions.execute(c -> daos.create(c).users().findByUsername(username).orElse(null));
        boolean valid = passwords.verify(password, user == null ? null : user.passwordHash());
        if (!valid || user == null || user.status() != UserStatus.ACTIVE) throw new AuthenticationException();
        // Revalidate status and hash after verification to reject a concurrent disable/password change.
        return transactions.execute(c -> {
            User current = daos.create(c).users().findById(user.id()).orElseThrow(AuthenticationException::new);
            if (current.status() != UserStatus.ACTIVE || !Objects.equals(current.passwordHash(), user.passwordHash())) {
                throw new AuthenticationException();
            }
            return AuthenticatedUser.from(current);
        });
    }

    @Override public AuthenticatedUser current(Long actorUserId) {
        if (actorUserId == null) throw new AuthenticationException();
        return transactions.execute(c -> {
            User user = daos.create(c).users().findById(actorUserId).orElseThrow(AuthenticationException::new);
            if (user.status() != UserStatus.ACTIVE) throw new AuthenticationException();
            return AuthenticatedUser.from(user);
        });
    }
}
