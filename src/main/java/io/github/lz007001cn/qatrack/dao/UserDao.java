package io.github.lz007001cn.qatrack.dao;

import io.github.lz007001cn.qatrack.model.User;
import java.util.Optional;

/** User persistence only. The caller owns the connection and transaction. */
public interface UserDao {
    Optional<User> findById(Long id);
    /** Requires an outer transaction; stabilizes actor status/role during a business write. */
    Optional<User> findByIdForShare(Long id);
    Optional<User> findByUsername(String username);
    /** Generated ID, timestamps and version come from MySQL; those input fields are ignored. */
    User insert(User user);
    /** Updates mutable columns using id + lockVersion; username/createdAt are never modified. */
    User update(User user);
}
