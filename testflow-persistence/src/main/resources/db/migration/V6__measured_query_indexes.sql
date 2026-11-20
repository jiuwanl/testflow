-- Based on the phase-6 synthetic-data EXPLAIN ANALYZE evidence.
CREATE INDEX idx_run_finished ON tf_test_run(project_id,finished_at,id);
ALTER TABLE tf_outbox_event DROP INDEX ix_outbox_dispatch,
  ADD INDEX ix_outbox_pending(status,next_attempt_at,event_id),
  ADD INDEX ix_outbox_expired(status,lease_until,event_id);
