package io.github.lz007001cn.veriqra.admin;

import io.github.lz007001cn.veriqra.service.support.ServiceTransaction;
import java.util.Objects;

/** Best-effort telemetry. Callers catch failures so business responses never depend on access logging. */
public final class TelemetryService {
    private final ServiceTransaction tx;
    public TelemetryService(ServiceTransaction tx){this.tx=Objects.requireNonNull(tx);}
    public void login(Long userId,String username,String ip,String userAgent,String result,String reason){
        UserAgentInfo ua=UserAgentInfo.from(userAgent);
        String safeName=username==null?"":username.replaceAll("[\\x00-\\x1F\\x7F]"," ");
        if(safeName.length()>64)safeName=safeName.substring(0,64);
        String address=ip==null?"":ip.length()>45?ip.substring(0,45):ip;
        var event=new LoginEvent(0,userId,safeName,address,ua.raw(),ua.browser(),ua.operatingSystem(),ua.deviceType(),result,reason,null);
        tx.execute(c->{new JdbcAdminLogDao(c).appendLogin(event);return null;});
    }
    public void access(Long userId,String ip,String userAgent,String method,String path,int status,
                       String requestId,int durationMs){
        UserAgentInfo ua=UserAgentInfo.from(userAgent);
        String address=ip==null?"":ip.length()>45?ip.substring(0,45):ip;
        String safeMethod=method==null?"":method.length()>16?method.substring(0,16):method;
        // URI paths may contain ;jsessionid or other path parameters even without a query string.
        String safePath=path==null?"":path.replaceAll("(?i)(?:;|%3b)[^/]*","");
        if(safePath.length()>512)safePath=safePath.substring(0,512);
        var event=new AccessEvent(0,userId,null,address,ua.raw(),ua.browser(),ua.operatingSystem(),ua.deviceType(),
                safeMethod,safePath,status,requestId,Math.max(0,durationMs),null);
        tx.execute(c->{new JdbcAdminLogDao(c).appendAccess(event);return null;});
    }
}
