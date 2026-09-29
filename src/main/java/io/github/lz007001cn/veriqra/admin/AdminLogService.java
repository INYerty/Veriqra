package io.github.lz007001cn.veriqra.admin;

import io.github.lz007001cn.veriqra.service.support.ServiceTransaction;
import java.time.Clock;
import java.util.*;

/** Paged Administration reads; no log edit/delete contract exists. */
public final class AdminLogService {
    private final ServiceTransaction tx;private final AdminAccessPolicy policy;private final Clock clock;
    public AdminLogService(ServiceTransaction tx,AdminAccessPolicy policy){this(tx,policy,Clock.systemUTC());}
    public AdminLogService(ServiceTransaction tx,AdminAccessPolicy policy,Clock clock){
        this.tx=Objects.requireNonNull(tx);this.policy=Objects.requireNonNull(policy);this.clock=Objects.requireNonNull(clock);
    }
    public void requireAdmin(long actor){tx.execute(c->{policy.requireRead(c,actor);return null;});}
    public Page<LoginEvent> logins(long actor,LogFilter filter,int page,int size){return tx.execute(c->{policy.requireRead(c,actor);return new JdbcAdminLogDao(c).logins(filter,page,size);});}
    public Page<SessionActivity> sessionActivity(long actor,int page,int size){return tx.execute(c->{policy.requireRead(c,actor);return new JdbcAdminLogDao(c).sessionActivity(page,size);});}
    public Page<AccessEvent> access(long actor,LogFilter filter,int page,int size){return tx.execute(c->{policy.requireRead(c,actor);return new JdbcAdminLogDao(c).access(filter,page,size);});}
    public Page<AuditEvent> audits(long actor,LogFilter filter,int page,int size){return tx.execute(c->{policy.requireRead(c,actor);return new JdbcAdminLogDao(c).audits(filter,page,size);});}
    public AdminMetrics metrics(long actor){
        AdminTodayWindow today=AdminTodayWindow.now(clock);
        return tx.execute(c->{policy.requireRead(c,actor);return new JdbcAdminLogDao(c).metrics(today);});
    }
    public String databaseVersion(long actor){return tx.execute(c->{policy.requireRead(c,actor);return new JdbcAdminLogDao(c).databaseVersion();});}
    public int databaseTableCount(long actor){return tx.execute(c->{policy.requireRead(c,actor);return new JdbcAdminLogDao(c).databaseTableCount();});}
}
