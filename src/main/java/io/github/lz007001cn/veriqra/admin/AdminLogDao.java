package io.github.lz007001cn.veriqra.admin;

public interface AdminLogDao {
    void appendLogin(LoginEvent event);
    void appendAccess(AccessEvent event);
    void appendAudit(AuditEvent event);
    Page<LoginEvent> logins(LogFilter filter,int page,int size);
    Page<SessionActivity> sessionActivity(int page,int size);
    Page<AccessEvent> access(LogFilter filter,int page,int size);
    Page<AuditEvent> audits(LogFilter filter,int page,int size);
    AdminMetrics metrics();
    String databaseVersion();
}
