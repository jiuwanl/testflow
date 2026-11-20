-- Stage 2: single-case durable execution. API is the only migration owner.
CREATE TABLE tf_test_run (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 requested_by BIGINT NOT NULL,
 idempotency_key VARCHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 kind VARCHAR(16) NOT NULL,
 source_id BIGINT NOT NULL,
 env_id BIGINT NOT NULL,
 source_name_snapshot VARCHAR(100) NOT NULL,
 environment_name_snapshot VARCHAR(100) NOT NULL,
 requested_by_name VARCHAR(32) NOT NULL,
 parent_run_id BIGINT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
 outcome VARCHAR(16) NULL,
 terminal_reason VARCHAR(64) NULL,
 owner_id VARCHAR(36) NULL,
 lease_version BIGINT NOT NULL DEFAULT 0,
 lease_until DATETIME(3) NULL,
 heartbeat_at DATETIME(3) NULL,
 created_at DATETIME(3) NOT NULL,
 queued_at DATETIME(3) NOT NULL,
 started_at DATETIME(3) NULL,
 finished_at DATETIME(3) NULL,
 deadline_at DATETIME(3) NOT NULL,
 total_count INT NOT NULL,
 passed_count INT NOT NULL DEFAULT 0,
 failed_count INT NOT NULL DEFAULT 0,
 error_count INT NOT NULL DEFAULT 0,
 skipped_count INT NOT NULL DEFAULT 0,
 cancelled_count INT NOT NULL DEFAULT 0,
 unknown_count INT NOT NULL DEFAULT 0,
 CONSTRAINT uk_run_idempotency UNIQUE(project_id,requested_by,idempotency_key),
 CONSTRAINT uk_run_project_id UNIQUE(project_id,id),
 CONSTRAINT fk_run_project FOREIGN KEY(project_id) REFERENCES tf_project(id),
 CONSTRAINT fk_run_requester FOREIGN KEY(requested_by) REFERENCES tf_user(id),
 CONSTRAINT fk_run_parent FOREIGN KEY(parent_run_id) REFERENCES tf_test_run(id),
 CONSTRAINT ck_run_kind CHECK(kind IN ('CASE','PLAN')),
 CONSTRAINT ck_run_state CHECK(state IN ('QUEUED','RUNNING','CANCELLING','COMPLETED','CANCELLED','TIMED_OUT','INTERRUPTED')),
 CONSTRAINT ck_run_outcome CHECK(outcome IS NULL OR outcome IN ('PASSED','FAILED','INCOMPLETE')),
 CONSTRAINT ck_run_counts CHECK(total_count BETWEEN 1 AND 100 AND passed_count>=0 AND failed_count>=0 AND error_count>=0 AND skipped_count>=0 AND cancelled_count>=0 AND unknown_count>=0 AND passed_count+failed_count+error_count+skipped_count+cancelled_count+unknown_count<=total_count),
 INDEX idx_run_claim(state,created_at,id),
 INDEX idx_run_lease(state,lease_until,id),
 INDEX idx_run_deadline(state,deadline_at,id),
 INDEX idx_run_history(project_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_run_snapshot (
 run_id BIGINT NOT NULL PRIMARY KEY,
 project_id BIGINT NOT NULL,
 schema_version INT NOT NULL,
 content_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 encrypted_payload MEDIUMTEXT NOT NULL,
 key_version VARCHAR(16) NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_snapshot_run FOREIGN KEY(project_id,run_id) REFERENCES tf_test_run(project_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_run_item (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 run_id BIGINT NOT NULL,
 ordinal INT NOT NULL,
 source_case_id BIGINT NOT NULL,
 case_name_snapshot VARCHAR(100) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
 started_at DATETIME(3) NULL,
 finished_at DATETIME(3) NULL,
 duration_ms BIGINT NULL,
 error_code VARCHAR(64) NULL,
 CONSTRAINT uk_item_ordinal UNIQUE(run_id,ordinal),
 CONSTRAINT uk_item_project_id UNIQUE(project_id,id),
 CONSTRAINT fk_item_run FOREIGN KEY(project_id,run_id) REFERENCES tf_test_run(project_id,id),
 CONSTRAINT ck_item_ordinal CHECK(ordinal BETWEEN 1 AND 100),
 CONSTRAINT ck_item_state CHECK(state IN ('PENDING','RUNNING','PASSED','FAILED','ERROR','SKIPPED','CANCELLED','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_run_item_result (
 item_id BIGINT NOT NULL PRIMARY KEY,
 project_id BIGINT NOT NULL,
 report_json JSON NOT NULL,
 body_truncated BOOLEAN NOT NULL DEFAULT FALSE,
 body_available BOOLEAN NOT NULL DEFAULT FALSE,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 CONSTRAINT fk_result_item FOREIGN KEY(project_id,item_id) REFERENCES tf_run_item(project_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE tf_audit_event ADD COLUMN run_id BIGINT NULL;
