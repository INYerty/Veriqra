package io.github.lz007001cn.veriqra.admin;

import io.github.lz007001cn.veriqra.dao.jdbc.JdbcUserDao;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.exception.ForbiddenException;
import java.sql.Connection;
import java.util.List;

/** All Administration services use this policy. Writes lock every active ADMIN in ID order. */
public final class AdminAccessPolicy {
    public User requireRead(Connection connection,long actorId) {
        User actor=new JdbcUserDao(connection).findById(actorId)
                .orElseThrow(()->new ForbiddenException("Administrator access required"));
        if(actor.status()!=UserStatus.ACTIVE || actor.systemRole()!=SystemRole.ADMIN)
            throw new ForbiddenException("Administrator access required");
        return actor;
    }
    public List<Long> requireWrite(Connection connection,long actorId) {
        // Serializes competing demotions/disables without an unprotected COUNT → UPDATE gap.
        List<Long> ids=new JdbcAdminUserDao(connection).lockActiveAdminIds();
        if(!ids.contains(actorId)) throw new ForbiddenException("Administrator access required");
        return ids;
    }
}
