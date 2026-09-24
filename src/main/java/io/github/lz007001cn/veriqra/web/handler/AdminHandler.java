package io.github.lz007001cn.veriqra.web.handler;

import io.github.lz007001cn.veriqra.admin.*;
import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.web.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.*;

/** One ADMIN gate for every /api/admin route; Services repeat the check inside each transaction. */
public final class AdminHandler {
    private AdminHandler() { }
    public record CreateUser(String username,String displayName,String password,SystemRole systemRole,UserStatus status) { }
    public record UpdateUser(String displayName,SystemRole systemRole,UserStatus status,Integer lockVersion) { }
    public record ResetPassword(String password) { }
    public record ChangeCredit(Long amount,String reason) { }
    public record BatchCredit(String scope,List<Long> userIds,List<Long> expectedActiveUserIds,Long amount,String reason) { }

    public static void handle(HttpServletRequest request,HttpServletResponse response,AdminServices admin,
                              long actor,String[] parts)throws IOException {
        admin.logs().requireAdmin(actor);
        String method=request.getMethod();
        int page=page(request,"page",1,1_000_000);
        int size=page(request,"pageSize",25,100);
        AdminContext ctx=context(request,actor);
        if(parts.length==3 && parts[2].equals("dashboard")) {
            JsonHttp.method(response,method,"GET");
            JsonHttp.write(response,200,Map.of("metrics",admin.logs().metrics(actor),"credits",admin.credits().summary(actor),
                    "system",Map.of("version","0.1.0-SNAPSHOT","applicationStatus","UP","databaseStatus","UP"),
                    "recentFailures",admin.logs().logins(actor,new LogFilter(null,null,null,"FAILURE",null,null,null,null,null,null),1,5).items(),
                    "recentActions",admin.logs().audits(actor,emptyFilter(),1,5).items()));return;
        }
        if(parts.length>=3 && parts[2].equals("users")) {
            users(request,response,admin,actor,ctx,parts,method,page,size);return;
        }
        if(parts.length>=3 && parts[2].equals("credits")) {
            credits(request,response,admin,actor,ctx,parts,method,page,size);return;
        }
        if(parts.length==3 && parts[2].equals("login-history")) {
            JsonHttp.method(response,method,"GET");JsonHttp.write(response,200,admin.logs().logins(actor,filter(request),page,size));return;
        }
        if(parts.length==3 && parts[2].equals("access-logs")) {
            JsonHttp.method(response,method,"GET");JsonHttp.write(response,200,admin.logs().access(actor,filter(request),page,size));return;
        }
        if(parts.length==3 && parts[2].equals("audit-logs")) {
            JsonHttp.method(response,method,"GET");JsonHttp.write(response,200,admin.logs().audits(actor,filter(request),page,size));return;
        }
        if(parts.length==3 && parts[2].equals("sessions")) {
            JsonHttp.method(response,method,"GET");
            // No global session registry exists: report successful logins and user-level last seen only.
            JsonHttp.write(response,200,Map.of("mode","LOGIN_ACTIVITY_ONLY","onlineStatusAvailable",false,
                    "activity",admin.logs().sessionActivity(actor,page,size)));return;
        }
        if(parts.length==3 && parts[2].equals("security")) {
            JsonHttp.method(response,method,"GET");JsonHttp.write(response,200,Map.of(
                    "metrics",admin.logs().metrics(actor),
                    "recentFailures",admin.logs().logins(actor,new LogFilter(null,null,null,"FAILURE",null,null,null,null,null,null),1,10).items(),
                    "recentRateLimits",admin.logs().logins(actor,new LogFilter(null,null,null,"RATE_LIMITED",null,null,null,null,null,null),1,10).items()));return;
        }
        if(parts.length==3 && parts[2].equals("system")) {
            JsonHttp.method(response,method,"GET");
            String origin=io.github.lz007001cn.veriqra.config.EnvironmentVariables.get("PUBLIC_ORIGIN");
            String secure=io.github.lz007001cn.veriqra.config.EnvironmentVariables.get("SESSION_SECURE");
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("veriqraVersion","0.1.0-SNAPSHOT");data.put("applicationStatus","UP");
            data.put("javaVersion",System.getProperty("java.version"));data.put("tomcatVersion",request.getServletContext().getServerInfo());
            data.put("databaseStatus","UP");data.put("databaseVersion",admin.logs().databaseVersion(actor));
            data.put("uptimeSeconds",Math.max(0,(System.currentTimeMillis()-admin.startedAtMillis())/1000));
            data.put("serverTime",java.time.Instant.now().toString());
            data.put("sessionSecureMode","true".equalsIgnoreCase(secure));
            data.put("publicOrigin",origin==null?"Not configured":origin);
            JsonHttp.write(response,200,data);return;
        }
        throw new HttpFailure(404,"NOT_FOUND","Resource not found");
    }

    private static void users(HttpServletRequest request,HttpServletResponse response,AdminServices admin,long actor,
                              AdminContext ctx,String[] parts,String method,int page,int size)throws IOException {
        if(parts.length==3){
            JsonHttp.method(response,method,"GET","POST");
            if(method.equals("GET")){JsonHttp.write(response,200,admin.users().list(actor,page,size));return;}
            CreateUser body=JsonHttp.read(request,CreateUser.class);
            var created=admin.users().create(ctx,body.username(),body.displayName(),body.password(),body.systemRole(),body.status());
            response.setHeader("Location",request.getContextPath()+"/api/admin/users/"+created.id());
            JsonHttp.write(response,201,created);return;
        }
        long id=JsonHttp.positiveId(parts[3]);
        if(parts.length==4){
            JsonHttp.method(response,method,"GET","PATCH");
            if(method.equals("GET")){JsonHttp.write(response,200,admin.users().get(actor,id));return;}
            UpdateUser body=JsonHttp.read(request,UpdateUser.class);
            if(body.lockVersion()==null)throw new HttpFailure(400,"VALIDATION","lockVersion is required");
            JsonHttp.write(response,200,admin.users().update(ctx,id,body.displayName(),body.systemRole(),body.status(),body.lockVersion()));return;
        }
        if(parts.length==5 && parts[4].equals("reset-password")){
            JsonHttp.method(response,method,"POST");
            admin.users().resetPassword(ctx,id,JsonHttp.read(request,ResetPassword.class).password());
            response.setStatus(204);return;
        }
        if(parts.length==5 && parts[4].equals("credits")){
            JsonHttp.method(response,method,"GET");
            JsonHttp.write(response,200,Map.of("account",admin.credits().account(actor,id),
                    "transactions",admin.credits().history(actor,id,null,null,null,null,null,null,1,10).items()));return;
        }
        if(parts.length==6 && parts[4].equals("credits") && (parts[5].equals("grant")||parts[5].equals("reclaim"))){
            JsonHttp.method(response,method,"POST");ChangeCredit body=JsonHttp.read(request,ChangeCredit.class);
            if(body.amount()==null)throw new HttpFailure(400,"INVALID_CREDIT_AMOUNT","Positive amount required");
            var value=parts[5].equals("grant")?admin.credits().grant(ctx,id,body.amount(),body.reason())
                    :admin.credits().reclaim(ctx,id,body.amount(),body.reason());
            JsonHttp.write(response,200,value);return;
        }
        throw new HttpFailure(404,"NOT_FOUND","Resource not found");
    }

    private static void credits(HttpServletRequest request,HttpServletResponse response,AdminServices admin,long actor,
                                AdminContext ctx,String[] parts,String method,int page,int size)throws IOException {
        if(parts.length==3){JsonHttp.method(response,method,"GET");JsonHttp.write(response,200,admin.credits().accounts(actor,page,size));return;}
        if(parts.length==4 && parts[3].equals("summary")){
            JsonHttp.method(response,method,"GET");JsonHttp.write(response,200,admin.credits().summary(actor));return;
        }
        if(parts.length==4 && parts[3].equals("recipients")){
            JsonHttp.method(response,method,"GET");JsonHttp.write(response,200,admin.credits().activeRecipientIds(actor));return;
        }
        if(parts.length==4 && parts[3].equals("transactions")){
            JsonHttp.method(response,method,"GET");
            JsonHttp.write(response,200,admin.credits().history(actor,optionalId(request,"userId"),value(request,"username"),value(request,"type"),
                    optionalId(request,"actorId"),value(request,"batchId"),date(request,"from"),date(request,"to"),page,size));return;
        }
        if(parts.length==4 && parts[3].equals("batch-grant")){
            JsonHttp.method(response,method,"POST");BatchCredit body=JsonHttp.read(request,BatchCredit.class);
            if(body.amount()==null || body.scope()==null || !List.of("ALL_ACTIVE_USERS","SELECTED_USERS").contains(body.scope()))
                throw new HttpFailure(400,"VALIDATION","Explicit batch scope and amount required");
            String id=admin.credits().batchGrant(ctx,body.userIds(),body.expectedActiveUserIds(),
                    body.scope().equals("ALL_ACTIVE_USERS"),body.amount(),body.reason());
            JsonHttp.write(response,201,Map.of("batchId",id));return;
        }
        throw new HttpFailure(404,"NOT_FOUND","Resource not found");
    }

    private static AdminContext context(HttpServletRequest request,long actor){
        Object value=request.getAttribute(AccessLogFilter.REQUEST_ID);
        String id=value instanceof String s?s:UUID.randomUUID().toString();
        return new AdminContext(actor,request.getRemoteAddr(),id);
    }
    private static int page(HttpServletRequest request,String name,int fallback,int max){
        String raw=request.getParameter(name);if(raw==null)return fallback;
        try{int value=Integer.parseInt(raw);if(value<1 || value>max)throw new NumberFormatException();return value;}
        catch(NumberFormatException e){throw new HttpFailure(400,"INVALID_PAGE","Invalid pagination value");}
    }
    private static String value(HttpServletRequest request,String name){
        String raw=request.getParameter(name);
        if(raw==null || raw.isBlank())return null;
        if(raw.length()>128)throw new HttpFailure(400,"VALIDATION","Filter value too long");
        return raw;
    }
    private static Long optionalId(HttpServletRequest request,String name){
        String raw=value(request,name);return raw==null?null:JsonHttp.positiveId(raw);
    }
    private static LocalDateTime date(HttpServletRequest request,String name){
        String raw=value(request,name);if(raw==null)return null;
        try{return LocalDateTime.parse(raw);}catch(DateTimeParseException e){throw new HttpFailure(400,"VALIDATION","Invalid date filter");}
    }
    private static LogFilter emptyFilter(){return new LogFilter(null,null,null,null,null,null,null,null,null,null);}
    private static LogFilter filter(HttpServletRequest request){
        Integer status=null;String code=value(request,"status");
        if(code!=null){try{status=Integer.parseInt(code);if(status<100||status>599)throw new NumberFormatException();}
            catch(NumberFormatException e){throw new HttpFailure(400,"VALIDATION","Invalid status filter");}}
        LocalDateTime from=date(request,"from"),to=date(request,"to");
        if(from!=null && to!=null && !from.isBefore(to))throw new HttpFailure(400,"VALIDATION","Invalid time range");
        return new LogFilter(from,to,value(request,"username"),value(request,"result"),value(request,"ip"),
                value(request,"deviceType"),status,value(request,"method"),value(request,"action"),optionalId(request,"actorId"));
    }
}
