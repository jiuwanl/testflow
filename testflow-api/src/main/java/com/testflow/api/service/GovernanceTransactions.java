package com.testflow.api.service;

import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.ApiException;
import com.testflow.persistence.mapper.UserMapper;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** User rows precede project rows. Each attempt owns a fresh, fully rollbackable transaction. */
@Component
public class GovernanceTransactions {
  private final TransactionTemplate transaction;
  private final CurrentAccount current;
  private final UserMapper users;

  public GovernanceTransactions(
      PlatformTransactionManager manager, CurrentAccount current, UserMapper users) {
    this.current = current;
    this.users = users;
    transaction = new TransactionTemplate(manager);
    transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  public <T> T execute(Collection<Long> involved, boolean protectAdmins, Supplier<T> work) {
    long actorId = current.get().getId();
    for (int attempt = 0; attempt < 3; attempt++) {
      try {
        return transaction.execute(
            status -> {
              List<Long> admins = protectAdmins ? users.activeAdminIds() : List.of();
              var ids = new TreeSet<Long>(involved);
              ids.add(actorId);
              ids.addAll(admins);
              for (long id : ids) {
                if (users.lockById(id) == null) throw ApiException.missing();
              }
              if (protectAdmins && !admins.equals(users.activeAdminIds()))
                throw new AdminSetChanged();
              current.get(); // Recheck the session/account after waiting for locks.
              return work.get();
            });
      } catch (PessimisticLockingFailureException | AdminSetChanged ex) {
        if (attempt == 2) throw new ApiException(409, "CONCURRENT_MODIFICATION", "并发操作冲突，请刷新后重试");
      }
    }
    throw new IllegalStateException("Unreachable transaction attempt");
  }

  private static final class AdminSetChanged extends RuntimeException {}
}
