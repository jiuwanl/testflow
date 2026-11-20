-- Stage 3: existing durable task model supports ORDERED steps and snapshot reruns.
CREATE INDEX idx_run_source_history ON tf_test_run(project_id,kind,source_id,created_at,id);
CREATE INDEX idx_run_environment_history ON tf_test_run(project_id,env_id,created_at,id);
CREATE INDEX idx_run_parent ON tf_test_run(project_id,parent_run_id,id);
