-- API remains the only migration owner. Existing runs keep ORDERED semantics.
ALTER TABLE tf_test_plan DROP CHECK ck_plan_mode,
 ADD COLUMN max_concurrency INT NOT NULL DEFAULT 1,
 ADD CONSTRAINT ck_plan_mode CHECK(execution_mode IN ('ORDERED','INDEPENDENT')),
 ADD CONSTRAINT ck_plan_concurrency CHECK(
   (execution_mode='ORDERED' AND max_concurrency=1) OR
   (execution_mode='INDEPENDENT' AND max_concurrency BETWEEN 1 AND 4));

ALTER TABLE tf_test_run
 ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'ORDERED',
 ADD COLUMN max_concurrency INT NOT NULL DEFAULT 1,
 ADD CONSTRAINT ck_run_concurrency CHECK(
   (execution_mode='ORDERED' AND max_concurrency=1) OR
   (execution_mode='INDEPENDENT' AND kind='PLAN' AND max_concurrency BETWEEN 1 AND 4));
