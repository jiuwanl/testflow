package com.testflow.api.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.testflow.api.security.CurrentAccount;
import com.testflow.api.web.ApiException;
import com.testflow.persistence.entity.UserEntity;
import com.testflow.persistence.mapper.UserMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class GovernanceTransactionsTest {
  @Test
  void deadlocksRollbackAndRetryInNewTransactionsBeforeSingleCommit() {
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    var current = mock(CurrentAccount.class);
    var users = mock(UserMapper.class);
    var actor = new UserEntity();
    actor.setId(4L);
    when(current.get()).thenReturn(actor);
    when(users.lockById(anyLong())).thenReturn(actor);
    var tx = new GovernanceTransactions(manager, current, users);
    var attempts = new AtomicInteger();
    String result =
        tx.execute(
            List.of(7L, 2L),
            false,
            () -> {
              if (attempts.incrementAndGet() < 3)
                throw new PessimisticLockingFailureException("injected deadlock");
              return "committed";
            });
    assertThat(result).isEqualTo("committed");
    verify(manager, times(3)).getTransaction(any());
    verify(manager, times(2)).rollback(any());
    verify(manager).commit(any());
    var order = inOrder(users);
    for (int attempt = 0; attempt < 3; attempt++) {
      order.verify(users).lockById(2L);
      order.verify(users).lockById(4L);
      order.verify(users).lockById(7L);
    }
  }

  @Test
  void exhaustedDeadlocksBecomeConflictAndBusinessErrorsAreNotRetried() {
    var manager = mock(PlatformTransactionManager.class);
    when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    var current = mock(CurrentAccount.class);
    var users = mock(UserMapper.class);
    var actor = new UserEntity();
    actor.setId(1L);
    when(current.get()).thenReturn(actor);
    when(users.lockById(anyLong())).thenReturn(actor);
    var tx = new GovernanceTransactions(manager, current, users);
    assertThatThrownBy(
            () ->
                tx.execute(
                    List.of(),
                    false,
                    () -> {
                      throw new PessimisticLockingFailureException("injected");
                    }))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.code()).isEqualTo("CONCURRENT_MODIFICATION"));
    verify(manager, times(3)).rollback(any());
    clearInvocations(manager);
    assertThatThrownBy(
            () ->
                tx.execute(
                    List.of(),
                    false,
                    () -> {
                      throw ApiException.conflict();
                    }))
        .isInstanceOf(ApiException.class);
    verify(manager).getTransaction(any());
    verify(manager).rollback(any());
    verify(manager, never()).commit(any());
  }
}
