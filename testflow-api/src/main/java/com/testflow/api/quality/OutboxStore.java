package com.testflow.api.quality;

import com.testflow.persistence.RunStore;
import com.testflow.persistence.mapper.*;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OutboxStore {
  private final OutboxMapper events;
  private final RunMapper runs;
  private final TransactionTemplate tx;

  public OutboxStore(OutboxMapper events, RunMapper runs, PlatformTransactionManager manager) {
    this.events = events;
    this.runs = runs;
    tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(2);
  }

  public OutboxMapper.Event claim() {
    return tx.execute(
        status -> {
          var event = events.expired();
          if (event == null) event = events.next();
          if (event == null) return null;
          String token = UUID.randomUUID().toString();
          events.claim(event.eventId(), token);
          return events.find(event.eventId());
        });
  }

  public boolean published(OutboxMapper.Event event) {
    return events.published(event.eventId(), event.claimToken()) == 1;
  }

  public void failed(OutboxMapper.Event event) {
    events.failed(event.eventId(), event.claimToken());
  }

  public int backfill(Long projectId, int limit) {
    int count = 0;
    for (long id : events.missingEvents(projectId, limit)) {
      boolean added =
          Boolean.TRUE.equals(
              tx.execute(
                  status -> {
                    var run = runs.lock(id);
                    if (run == null
                        || !RunStore.terminal(run.getState())
                        || events.byRun(id) != null) return false;
                    return runs.enqueueFinalized(id, UUID.randomUUID().toString()) == 1;
                  }));
      if (added) count++;
    }
    return count;
  }
}
