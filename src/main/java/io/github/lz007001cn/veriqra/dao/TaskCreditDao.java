package io.github.lz007001cn.veriqra.dao;

import io.github.lz007001cn.veriqra.model.*;
import java.time.LocalDateTime;
import java.util.*;

/** Connection-scoped append-only credit, transfer and contribution persistence. */
public interface TaskCreditDao {
    Optional<CreditTransfer> findTransfer(String id);
    CreditTransfer insertTransfer(String id, long projectId, long senderId, long recipientId,
                                  long amount, String kind, Long offerId, String note);
    List<CreditTransfer> listTransfers(long userId, int limit);
    void appendLedger(long userId, long amount, String type, long actorId, String note,
                      long projectId, String transferId, Long taskId);
    void appendContribution(long projectId, long userId, long taskId, LocalDateTime acceptedAtUtc);
    List<MonthlyContribution> monthlyContribution(long projectId, LocalDateTime fromUtc, LocalDateTime toUtc);
    Optional<TaskHandoffOffer> findOffer(long id);
    Optional<TaskHandoffOffer> lockOffer(long id);
    Optional<TaskHandoffOffer> findOfferByRequestKey(String key);
    TaskHandoffOffer insertOffer(String key, long projectId, long taskId, long fromId, long toId,
                                  long amount, String note);
    List<TaskHandoffOffer> listOffers(long projectId, long actorId);
    void resolveOffer(long id, String status);
    void cancelPendingOffersForTask(long taskId);
}
