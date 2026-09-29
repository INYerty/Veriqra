package io.github.lz007001cn.veriqra.admin;

import io.github.lz007001cn.veriqra.service.exception.*;
import io.github.lz007001cn.veriqra.service.support.ServiceTransaction;
import java.util.*;

/** Experimental integer Credits: account + immutable ledger + audit in one transaction. */
public final class CreditService {
    public static final int MAX_BATCH = 500;
    private final ServiceTransaction tx;
    private final AdminAccessPolicy policy;
    public CreditService(ServiceTransaction tx,AdminAccessPolicy policy) {
        this.tx=Objects.requireNonNull(tx);this.policy=Objects.requireNonNull(policy);
    }
    public Page<CreditAccountView> accounts(long actor,int page,int size) {
        return tx.execute(c->{policy.requireRead(c,actor);return new JdbcCreditDao(c).listAccounts(page,size);});
    }
    public CreditAccountView account(long actor,long userId) {
        return tx.execute(c->{policy.requireRead(c,actor);return new JdbcCreditDao(c).findAccount(userId)
                .orElseThrow(()->new NotFoundException("Credit account not found"));});
    }
    public CreditSummary summary(long actor) {
        return tx.execute(c->{policy.requireRead(c,actor);return new JdbcCreditDao(c).summary();});
    }
    public List<Long> activeRecipientIds(long actor) {
        return tx.execute(c->{
            policy.requireRead(c,actor);
            List<Long> ids=new JdbcAdminUserDao(c).activeUserIds(MAX_BATCH);
            if(ids.size()>MAX_BATCH)throw new ValidationException("BATCH_TOO_LARGE");
            return ids;
        });
    }
    public Page<CreditEntry> history(long actor,Long userId,String username,String type,Long actorId,String batchId,
                                     java.time.LocalDateTime from,java.time.LocalDateTime to,int page,int size) {
        if(type!=null && !List.of("GRANT","RECLAIM","PEER_TRANSFER_OUT","PEER_TRANSFER_IN",
                "HANDOFF_OUT","HANDOFF_IN","TASK_REWARD").contains(type)) throw new ValidationException("Invalid credit type");
        return tx.execute(c->{policy.requireRead(c,actor);return new JdbcCreditDao(c).listEntries(userId,username,type,actorId,batchId,from,to,page,size);});
    }
    public CreditAccountView grant(AdminContext ctx,long userId,long amount,String reason) {
        return change(ctx,userId,amount,reason,"GRANT");
    }
    public CreditAccountView reclaim(AdminContext ctx,long userId,long amount,String reason) {
        return change(ctx,userId,amount,reason,"RECLAIM");
    }
    private CreditAccountView change(AdminContext ctx,long userId,long amount,String reason,String type) {
        requireAmount(amount);String note=reason(reason);
        return tx.execute(c->{
            policy.requireWrite(c,ctx.actorId());
            var credits=new JdbcCreditDao(c);
            var before=credits.lockAccount(userId).orElseThrow(()->new NotFoundException("Credit account not found"));
            long balance;
            if(type.equals("RECLAIM")) {
                if(before.balance()<amount) throw new AdminConflictException("INSUFFICIENT_CREDIT_BALANCE","Insufficient Credit balance");
                balance=before.balance()-amount;
            } else {
                try{balance=Math.addExact(before.balance(),amount);}
                catch(ArithmeticException e){throw new ConflictException("Credit balance overflow");}
            }
            credits.updateBalance(before,balance);
            credits.append(userId,amount,type,ctx.actorId(),note,null);
            new JdbcAdminLogDao(c).appendAudit(new AuditEvent(0,ctx.actorId(),
                    type.equals("GRANT")?"CREDIT_GRANTED":"CREDIT_RECLAIMED","USER",userId,
                    "Experimental Credits "+type.toLowerCase(Locale.ROOT),null,ctx.ipAddress(),ctx.requestId(),null));
            return credits.findAccount(userId).orElseThrow();
        });
    }
    public String batchGrant(AdminContext ctx,List<Long> requestedIds,List<Long> expectedActiveIds,
                             boolean allActive,long amount,String reason) {
        requireAmount(amount);String note=reason(reason);
        if(allActive && requestedIds!=null && !requestedIds.isEmpty()) throw new ValidationException("Choose one batch scope");
        if(allActive && (expectedActiveIds==null || expectedActiveIds.isEmpty() || expectedActiveIds.size()>MAX_BATCH
                || expectedActiveIds.stream().anyMatch(id->id==null || id<=0)
                || new HashSet<>(expectedActiveIds).size()!=expectedActiveIds.size()))
            throw new ValidationException("An explicit active-user preview is required");
        if(!allActive && expectedActiveIds!=null && !expectedActiveIds.isEmpty())
            throw new ValidationException("Choose one batch scope");
        if(!allActive && (requestedIds==null || requestedIds.isEmpty())) throw new ValidationException("Select recipients");
        if(!allActive && (requestedIds.size()>MAX_BATCH || requestedIds.stream().anyMatch(id->id==null || id<=0)
                || new HashSet<>(requestedIds).size()!=requestedIds.size())) throw new ValidationException("Invalid batch recipients");
        return tx.execute(c->{
            policy.requireWrite(c,ctx.actorId());
            var users=new JdbcAdminUserDao(c);
            List<Long> recipients=allActive?users.activeUserIds(MAX_BATCH):new ArrayList<>(requestedIds);
            if(recipients.isEmpty() || recipients.size()>MAX_BATCH)throw new ValidationException("BATCH_TOO_LARGE or empty");
            recipients.sort(Long::compareTo);
            if(allActive) {
                List<Long> expected=new ArrayList<>(expectedActiveIds);
                expected.sort(Long::compareTo);
                if(!recipients.equals(expected))
                    throw new AdminConflictException("BATCH_RECIPIENTS_CHANGED","Active users changed; review the batch again");
            }
            long total;
            try{total=Math.multiplyExact(amount,recipients.size());}
            catch(ArithmeticException e){throw new ValidationException("Batch total overflow");}
            String batchId=UUID.randomUUID().toString();
            var credits=new JdbcCreditDao(c);
            for(long id:recipients) {
                var before=credits.lockAccount(id).orElseThrow(()->new NotFoundException("Credit account not found"));
                long balance;
                try{balance=Math.addExact(before.balance(),amount);}
                catch(ArithmeticException e){throw new ConflictException("Credit balance overflow");}
                credits.updateBalance(before,balance);
                credits.append(id,amount,"GRANT",ctx.actorId(),note,batchId);
            }
            String metadata="{\"recipientCount\":"+recipients.size()+",\"amountPerUser\":"+amount
                    +",\"totalAmount\":"+total+",\"batchId\":\""+batchId+"\"}";
            new JdbcAdminLogDao(c).appendAudit(new AuditEvent(0,ctx.actorId(),"CREDIT_BATCH_GRANTED","BATCH",null,
                    "Experimental Credits batch granted",metadata,ctx.ipAddress(),ctx.requestId(),null));
            return batchId;
        });
    }
    private static void requireAmount(long amount) {
        if(amount<=0)throw new ValidationException("INVALID_CREDIT_AMOUNT");
    }
    private static String reason(String value) {
        if(value==null || value.isBlank())return null;
        if(value.length()>500)throw new ValidationException("Reason too long");
        return value.strip();
    }
}
