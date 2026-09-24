package io.github.lz007001cn.veriqra.admin;

import java.time.LocalDateTime;
import java.util.*;

/** Caller owns one transaction and Connection. Ledger has insert/query only. */
public interface CreditDao {
    record LockedAccount(long userId, long balance, int version) { }
    void createAccount(long userId);
    Optional<LockedAccount> lockAccount(long userId);
    void updateBalance(LockedAccount before, long after);
    CreditEntry append(long userId, long amount, String type, long actorId, String reason, String batchId);
    Page<CreditAccountView> listAccounts(int page, int pageSize);
    Optional<CreditAccountView> findAccount(long userId);
    Page<CreditEntry> listEntries(Long userId, String username, String type, Long actorId, String batchId,
                                  LocalDateTime from, LocalDateTime to, int page, int pageSize);
    CreditSummary summary();
}
