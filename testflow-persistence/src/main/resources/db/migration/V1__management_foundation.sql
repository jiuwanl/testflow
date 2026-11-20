-- Stage 1 only: management definitions, audit and non-executing Worker heartbeat.
CREATE TABLE tf_user (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 username VARCHAR(32) NOT NULL,
 password_hash VARCHAR(100) NOT NULL,
 global_admin BOOLEAN NOT NULL DEFAULT FALSE,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_by BIGINT NULL,
 updated_by BIGINT NULL,
 CONSTRAINT uk_user_username UNIQUE (username),
 CONSTRAINT ck_user_status CHECK (status IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_project (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 name VARCHAR(100) NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_by BIGINT NOT NULL,
 updated_by BIGINT NOT NULL,
 CONSTRAINT ck_project_status CHECK (status IN ('ACTIVE','ARCHIVED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_project_member (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 user_id BIGINT NOT NULL,
 role VARCHAR(16) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_by BIGINT NOT NULL,
 updated_by BIGINT NOT NULL,
 CONSTRAINT uk_member UNIQUE (project_id,user_id),
 CONSTRAINT fk_member_project FOREIGN KEY (project_id) REFERENCES tf_project(id),
 CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES tf_user(id),
 CONSTRAINT ck_member_role CHECK (role IN ('OWNER','MAINTAINER','VIEWER')),
 INDEX idx_member_user (user_id,project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_target_policy (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 scheme VARCHAR(8) NOT NULL,
 host VARCHAR(253) NOT NULL,
 port INT NOT NULL,
 address_scope VARCHAR(16) NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_by BIGINT NOT NULL,
 updated_by BIGINT NOT NULL,
 CONSTRAINT fk_target_project FOREIGN KEY (project_id) REFERENCES tf_project(id),
 CONSTRAINT uk_target UNIQUE(project_id,scheme,host,port),
 CONSTRAINT ck_target_scheme CHECK(scheme IN ('http','https')),
 CONSTRAINT ck_target_scope CHECK(address_scope IN ('PUBLIC','PRIVATE','LOOPBACK')),
 CONSTRAINT ck_target_port CHECK(port BETWEEN 1 AND 65535)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_environment (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 base_url VARCHAR(2048) NOT NULL,
 variables_json JSON NOT NULL,
 secret_ciphertext MEDIUMTEXT NOT NULL,
 key_version VARCHAR(16) NOT NULL DEFAULT 'v1',
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_by BIGINT NOT NULL,
 updated_by BIGINT NOT NULL,
 CONSTRAINT uk_environment_name UNIQUE(project_id,name),
 CONSTRAINT fk_environment_project FOREIGN KEY (project_id) REFERENCES tf_project(id),
 CONSTRAINT ck_environment_status CHECK(status IN ('ACTIVE','ARCHIVED')),
 INDEX idx_environment_list(project_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_api_case (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 tags_json JSON NOT NULL,
 priority VARCHAR(8) NOT NULL DEFAULT 'P2',
 config_schema_version INT NOT NULL DEFAULT 1,
 config_json JSON NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_by BIGINT NOT NULL,
 updated_by BIGINT NOT NULL,
 CONSTRAINT uk_case_project_id UNIQUE(project_id,id),
 CONSTRAINT fk_case_project FOREIGN KEY(project_id) REFERENCES tf_project(id),
 CONSTRAINT ck_case_status CHECK(status IN ('ACTIVE','ARCHIVED')),
 CONSTRAINT ck_case_priority CHECK(priority IN ('P0','P1','P2','P3')),
 CONSTRAINT ck_case_config_version CHECK(config_schema_version=1),
 INDEX idx_case_list(project_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_test_plan (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 name VARCHAR(100) NOT NULL,
 execution_mode VARCHAR(16) NOT NULL DEFAULT 'ORDERED',
 fail_fast BOOLEAN NOT NULL DEFAULT TRUE,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_by BIGINT NOT NULL,
 updated_by BIGINT NOT NULL,
 CONSTRAINT uk_plan_project_id UNIQUE(project_id,id),
 CONSTRAINT fk_plan_project FOREIGN KEY(project_id) REFERENCES tf_project(id),
 CONSTRAINT ck_plan_status CHECK(status IN ('ACTIVE','ARCHIVED')),
 CONSTRAINT ck_plan_mode CHECK(execution_mode='ORDERED'),
 INDEX idx_plan_list(project_id,status,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_plan_step (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NOT NULL,
 plan_id BIGINT NOT NULL,
 case_id BIGINT NOT NULL,
 ordinal INT NOT NULL,
 enabled BOOLEAN NOT NULL DEFAULT TRUE,
 CONSTRAINT uk_plan_ordinal UNIQUE(plan_id,ordinal),
 CONSTRAINT fk_step_plan FOREIGN KEY(project_id,plan_id) REFERENCES tf_test_plan(project_id,id),
 CONSTRAINT fk_step_case FOREIGN KEY(project_id,case_id) REFERENCES tf_api_case(project_id,id),
 CONSTRAINT ck_step_ordinal CHECK(ordinal>=1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_worker_node (
 worker_id VARCHAR(36) NOT NULL PRIMARY KEY,
 application_version VARCHAR(32) NOT NULL,
 expected_schema_version VARCHAR(32) NOT NULL,
 last_seen_at DATETIME(3) NOT NULL,
 started_at DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE tf_audit_event (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 project_id BIGINT NULL,
 actor_id BIGINT NOT NULL,
 action VARCHAR(64) NOT NULL,
 resource_type VARCHAR(32) NOT NULL,
 resource_id BIGINT NULL,
 summary_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX idx_audit_project_time(project_id,created_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

