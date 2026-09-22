-- Veriqra V1 Domain Model Freeze v1.0
-- MySQL 8.0.46; execute in an EMPTY, explicitly selected database.
-- No CREATE DATABASE / USE / DROP / IF NOT EXISTS: never hide a partial installation.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION time_zone = '+00:00';
SET SESSION sql_mode = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';

CREATE TABLE `users` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `username` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `display_name` VARCHAR(80) NOT NULL,
  `password_hash` VARCHAR(255) NOT NULL,
  `system_role` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'USER',
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_users_username` (`username`),
  CONSTRAINT `ck_users_username_nonblank` CHECK (CHAR_LENGTH(TRIM(`username`)) > 0),
  CONSTRAINT `ck_users_display_name_nonblank` CHECK (CHAR_LENGTH(TRIM(`display_name`)) > 0),
  CONSTRAINT `ck_users_password_hash_nonblank` CHECK (CHAR_LENGTH(TRIM(`password_hash`)) > 0),
  CONSTRAINT `ck_users_system_role` CHECK (`system_role` IN ('ADMIN','USER')),
  CONSTRAINT `ck_users_status` CHECK (`status` IN ('ACTIVE','DISABLED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `projects` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_key` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `name` VARCHAR(160) NOT NULL,
  `description` TEXT NULL DEFAULT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_projects_key` (`project_key`),
  KEY `ix_projects_created_by` (`created_by`),
  CONSTRAINT `fk_projects_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_projects_name_nonblank` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0),
  CONSTRAINT `ck_projects_status` CHECK (`status` IN ('ACTIVE','ARCHIVED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `project_members` (
  `project_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `project_role` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `joined_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`project_id`, `user_id`),
  KEY `ix_members_user_status_project` (`user_id`, `status`, `project_id`),
  CONSTRAINT `fk_project_members_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_project_members_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_project_members_project_role` CHECK (`project_role` IN ('TESTER','DEVELOPER')),
  CONSTRAINT `ck_project_members_status` CHECK (`status` IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `project_counters` (
  `project_id` BIGINT NOT NULL,
  `entity_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `next_value` BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (`project_id`, `entity_type`),
  CONSTRAINT `fk_project_counters_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_project_counters_entity_type` CHECK (`entity_type` IN ('REQ','TC','PLAN','BUG')),
  CONSTRAINT `ck_project_counters_next_value` CHECK (`next_value` > 0)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `requirements` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `key_no` BIGINT NOT NULL,
  `title` VARCHAR(240) NOT NULL,
  `description` TEXT NULL DEFAULT NULL,
  `priority` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MEDIUM',
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_requirements_project_key` (`project_id`, `key_no`),
  KEY `ix_requirements_created_by` (`created_by`),
  CONSTRAINT `fk_requirements_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_requirements_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_requirements_key_no` CHECK (`key_no` > 0),
  CONSTRAINT `ck_requirements_title_nonblank` CHECK (CHAR_LENGTH(TRIM(`title`)) > 0),
  CONSTRAINT `ck_requirements_priority` CHECK (`priority` IN ('LOW','MEDIUM','HIGH')),
  CONSTRAINT `ck_requirements_status` CHECK (`status` IN ('DRAFT','ACTIVE','ARCHIVED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_cases` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `key_no` BIGINT NOT NULL,
  `title` VARCHAR(240) NOT NULL,
  `description` TEXT NULL DEFAULT NULL,
  `preconditions` TEXT NULL DEFAULT NULL,
  `priority` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MEDIUM',
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_test_cases_project_key` (`project_id`, `key_no`),
  KEY `ix_test_cases_created_by` (`created_by`),
  CONSTRAINT `fk_test_cases_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_cases_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_cases_key_no` CHECK (`key_no` > 0),
  CONSTRAINT `ck_test_cases_title_nonblank` CHECK (CHAR_LENGTH(TRIM(`title`)) > 0),
  CONSTRAINT `ck_test_cases_priority` CHECK (`priority` IN ('LOW','MEDIUM','HIGH')),
  CONSTRAINT `ck_test_cases_status` CHECK (`status` IN ('DRAFT','READY','ARCHIVED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_automation_identities` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `source` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'JUNIT',
  `namespace` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `external_key` VARCHAR(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_automation_identity` (`project_id`, `source`, `namespace`, `external_key`),
  CONSTRAINT `fk_test_automation_identities_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_automation_identities_source` CHECK (`source` IN ('JUNIT')),
  CONSTRAINT `ck_test_automation_identities_namespace_nonblank` CHECK (CHAR_LENGTH(TRIM(`namespace`)) > 0),
  CONSTRAINT `ck_automation_external_key_nonempty` CHECK (CHAR_LENGTH(`external_key`) > 0)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_automation_mappings` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `automation_identity_id` BIGINT NOT NULL,
  `test_case_id` BIGINT NOT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_automation_mapping_identity` (`automation_identity_id`),
  KEY `ix_automation_mappings_case_status` (`test_case_id`, `status`, `id`),
  KEY `ix_automation_mappings_created_by` (`created_by`),
  CONSTRAINT `fk_test_automation_mappings_automation_identity_id` FOREIGN KEY (`automation_identity_id`) REFERENCES `test_automation_identities` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_automation_mappings_test_case_id` FOREIGN KEY (`test_case_id`) REFERENCES `test_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_automation_mappings_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_automation_mappings_status` CHECK (`status` IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_steps` (
  `test_case_id` BIGINT NOT NULL,
  `step_order` SMALLINT UNSIGNED NOT NULL,
  `action` TEXT NOT NULL,
  `expected_result` TEXT NOT NULL,
  PRIMARY KEY (`test_case_id`, `step_order`),
  CONSTRAINT `fk_test_steps_test_case_id` FOREIGN KEY (`test_case_id`) REFERENCES `test_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_steps_step_order` CHECK (`step_order` > 0),
  CONSTRAINT `ck_test_steps_action_nonblank` CHECK (CHAR_LENGTH(TRIM(`action`)) > 0),
  CONSTRAINT `ck_test_steps_expected_result_nonblank` CHECK (CHAR_LENGTH(TRIM(`expected_result`)) > 0)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_case_requirements` (
  `requirement_id` BIGINT NOT NULL,
  `test_case_id` BIGINT NOT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'NEEDS_REVIEW',
  `linked_by` BIGINT NOT NULL,
  `linked_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `reviewed_by` BIGINT NULL DEFAULT NULL,
  `reviewed_at` DATETIME(6) NULL DEFAULT NULL,
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`requirement_id`, `test_case_id`),
  KEY `ix_trace_case_requirement` (`test_case_id`, `requirement_id`),
  KEY `ix_test_case_requirements_linked_by` (`linked_by`),
  KEY `ix_test_case_requirements_reviewed_by` (`reviewed_by`),
  CONSTRAINT `fk_test_case_requirements_requirement_id` FOREIGN KEY (`requirement_id`) REFERENCES `requirements` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_case_requirements_test_case_id` FOREIGN KEY (`test_case_id`) REFERENCES `test_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_case_requirements_linked_by` FOREIGN KEY (`linked_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_case_requirements_reviewed_by` FOREIGN KEY (`reviewed_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_case_requirements_status` CHECK (`status` IN ('CONFIRMED','NEEDS_REVIEW','REMOVED')),
  CONSTRAINT `ck_trace_review_shape` CHECK ((`status` = 'CONFIRMED' AND `reviewed_by` IS NOT NULL AND `reviewed_at` IS NOT NULL) OR (`status` IN ('NEEDS_REVIEW','REMOVED') AND `reviewed_by` IS NULL AND `reviewed_at` IS NULL))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_plans` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `key_no` BIGINT NOT NULL,
  `name` VARCHAR(160) NOT NULL,
  `description` TEXT NULL DEFAULT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_test_plans_project_key` (`project_id`, `key_no`),
  KEY `ix_test_plans_created_by` (`created_by`),
  CONSTRAINT `fk_test_plans_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_plans_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_plans_key_no` CHECK (`key_no` > 0),
  CONSTRAINT `ck_test_plans_name_nonblank` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0),
  CONSTRAINT `ck_test_plans_status` CHECK (`status` IN ('DRAFT','READY','ARCHIVED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_plan_cases` (
  `test_plan_id` BIGINT NOT NULL,
  `test_case_id` BIGINT NOT NULL,
  `added_by` BIGINT NOT NULL,
  `added_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`test_plan_id`, `test_case_id`),
  KEY `ix_plan_cases_case_plan` (`test_case_id`, `test_plan_id`),
  KEY `ix_test_plan_cases_added_by` (`added_by`),
  CONSTRAINT `fk_test_plan_cases_test_plan_id` FOREIGN KEY (`test_plan_id`) REFERENCES `test_plans` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_plan_cases_test_case_id` FOREIGN KEY (`test_case_id`) REFERENCES `test_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_plan_cases_added_by` FOREIGN KEY (`added_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_runs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `test_plan_id` BIGINT NULL DEFAULT NULL,
  `name` VARCHAR(160) NOT NULL,
  `environment` VARCHAR(160) NULL DEFAULT NULL,
  `build_version` VARCHAR(80) NULL DEFAULT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'IN_PROGRESS',
  `ended_at` DATETIME(6) NULL DEFAULT NULL,
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_runs_plan_created_id` (`test_plan_id`, `created_at`, `id`),
  KEY `ix_test_runs_created_by` (`created_by`),
  KEY `ix_runs_project_created_id` (`project_id`, `created_at`, `id`),
  CONSTRAINT `fk_test_runs_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_runs_test_plan_id` FOREIGN KEY (`test_plan_id`) REFERENCES `test_plans` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_runs_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_runs_name_nonblank` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0),
  CONSTRAINT `ck_test_runs_status` CHECK (`status` IN ('IN_PROGRESS','COMPLETED','CANCELLED')),
  CONSTRAINT `ck_runs_ended_at` CHECK ((`status` <> 'IN_PROGRESS' OR `ended_at` IS NULL) AND (`status` NOT IN ('COMPLETED','CANCELLED') OR (`ended_at` IS NOT NULL AND `ended_at` >= `created_at`)))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_run_cases` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `test_run_id` BIGINT NOT NULL,
  `test_case_id` BIGINT NOT NULL,
  `snapshot_title` VARCHAR(240) NOT NULL,
  `snapshot_description` TEXT NULL DEFAULT NULL,
  `snapshot_preconditions` TEXT NULL DEFAULT NULL,
  `snapshot_priority` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `captured_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_run_cases_run_case` (`test_run_id`, `test_case_id`),
  KEY `ix_run_cases_case_run` (`test_case_id`, `test_run_id`),
  CONSTRAINT `fk_test_run_cases_test_run_id` FOREIGN KEY (`test_run_id`) REFERENCES `test_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_run_cases_test_case_id` FOREIGN KEY (`test_case_id`) REFERENCES `test_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_run_cases_snapshot_title_nonblank` CHECK (CHAR_LENGTH(TRIM(`snapshot_title`)) > 0),
  CONSTRAINT `ck_test_run_cases_snapshot_priority` CHECK (`snapshot_priority` IN ('LOW','MEDIUM','HIGH'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_run_case_steps` (
  `test_run_case_id` BIGINT NOT NULL,
  `step_order` SMALLINT UNSIGNED NOT NULL,
  `action` TEXT NOT NULL,
  `expected_result` TEXT NOT NULL,
  PRIMARY KEY (`test_run_case_id`, `step_order`),
  CONSTRAINT `fk_test_run_case_steps_test_run_case_id` FOREIGN KEY (`test_run_case_id`) REFERENCES `test_run_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_run_case_steps_step_order` CHECK (`step_order` > 0),
  CONSTRAINT `ck_test_run_case_steps_action_nonblank` CHECK (CHAR_LENGTH(TRIM(`action`)) > 0),
  CONSTRAINT `ck_test_run_case_steps_expected_result_nonblank` CHECK (CHAR_LENGTH(TRIM(`expected_result`)) > 0)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_imports` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `test_run_id` BIGINT NOT NULL,
  `request_key` BINARY(16) NOT NULL,
  `report_sha256` BINARY(32) NOT NULL,
  `source_namespace` VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
  `original_filename` VARCHAR(255) NOT NULL,
  `imported_by` BIGINT NOT NULL,
  `imported_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_imports_request` (`request_key`),
  KEY `ix_imports_run_time_id` (`test_run_id`, `imported_at`, `id`),
  KEY `ix_test_imports_imported_by` (`imported_by`),
  CONSTRAINT `fk_test_imports_test_run_id` FOREIGN KEY (`test_run_id`) REFERENCES `test_runs` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_imports_imported_by` FOREIGN KEY (`imported_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_imports_source_namespace_nonblank` CHECK (CHAR_LENGTH(TRIM(`source_namespace`)) > 0),
  CONSTRAINT `ck_test_imports_original_filename_nonblank` CHECK (CHAR_LENGTH(TRIM(`original_filename`)) > 0)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_attempts` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `test_run_case_id` BIGINT NOT NULL,
  `attempt_no` INT UNSIGNED NOT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `executed_by` BIGINT NULL DEFAULT NULL,
  `import_id` BIGINT NULL DEFAULT NULL,
  `automation_mapping_id` BIGINT NULL DEFAULT NULL,
  `executed_at` DATETIME(6) NULL DEFAULT NULL,
  `recorded_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `duration_ms` BIGINT UNSIGNED NULL DEFAULT NULL,
  `comment` TEXT NULL DEFAULT NULL,
  `failure_message` TEXT NULL DEFAULT NULL,
  `submission_key` BINARY(16) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_attempts_run_case_no` (`test_run_case_id`, `attempt_no`),
  UNIQUE KEY `uq_attempts_submission` (`submission_key`),
  UNIQUE KEY `uq_attempts_import_run_case` (`import_id`, `test_run_case_id`),
  KEY `ix_test_attempts_executed_by` (`executed_by`),
  KEY `ix_attempts_automation_mapping` (`automation_mapping_id`),
  CONSTRAINT `fk_test_attempts_test_run_case_id` FOREIGN KEY (`test_run_case_id`) REFERENCES `test_run_cases` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_attempts_executed_by` FOREIGN KEY (`executed_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_attempts_import_id` FOREIGN KEY (`import_id`) REFERENCES `test_imports` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_attempts_automation_mapping_id` FOREIGN KEY (`automation_mapping_id`) REFERENCES `test_automation_mappings` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_test_attempts_attempt_no` CHECK (`attempt_no` > 0),
  CONSTRAINT `ck_test_attempts_status` CHECK (`status` IN ('PASS','FAIL','BLOCKED','SKIPPED')),
  CONSTRAINT `ck_attempts_failure_message` CHECK (`status` = 'FAIL' OR `failure_message` IS NULL),
  CONSTRAINT `ck_attempts_source_shape` CHECK ((`import_id` IS NULL AND `automation_mapping_id` IS NULL AND `executed_by` IS NOT NULL) OR (`import_id` IS NOT NULL AND `automation_mapping_id` IS NOT NULL AND `executed_by` IS NULL))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `defects` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `key_no` BIGINT NOT NULL,
  `title` VARCHAR(240) NOT NULL,
  `description` TEXT NULL DEFAULT NULL,
  `severity` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MEDIUM',
  `priority` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MEDIUM',
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
  `reporter_id` BIGINT NOT NULL,
  `assignee_id` BIGINT NULL DEFAULT NULL,
  `resolution_note` TEXT NULL DEFAULT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_defects_project_key` (`project_id`, `key_no`),
  KEY `ix_defects_assignee_status_id` (`assignee_id`, `status`, `id`),
  KEY `ix_defects_reporter_id` (`reporter_id`),
  CONSTRAINT `fk_defects_project_id` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_defects_reporter_id` FOREIGN KEY (`reporter_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_defects_assignee_id` FOREIGN KEY (`assignee_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_defects_key_no` CHECK (`key_no` > 0),
  CONSTRAINT `ck_defects_title_nonblank` CHECK (CHAR_LENGTH(TRIM(`title`)) > 0),
  CONSTRAINT `ck_defects_severity` CHECK (`severity` IN ('LOW','MEDIUM','HIGH','CRITICAL')),
  CONSTRAINT `ck_defects_priority` CHECK (`priority` IN ('LOW','MEDIUM','HIGH')),
  CONSTRAINT `ck_defects_status` CHECK (`status` IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED')),
  CONSTRAINT `ck_defects_resolution_note` CHECK (`status` NOT IN ('RESOLVED','CLOSED') OR (`resolution_note` IS NOT NULL AND CHAR_LENGTH(TRIM(`resolution_note`)) > 0))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `test_attempt_defects` (
  `attempt_id` BIGINT NOT NULL,
  `defect_id` BIGINT NOT NULL,
  `linked_by` BIGINT NOT NULL,
  `linked_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`attempt_id`, `defect_id`),
  KEY `ix_attempt_defects_defect_attempt` (`defect_id`, `attempt_id`),
  KEY `ix_test_attempt_defects_linked_by` (`linked_by`),
  CONSTRAINT `fk_test_attempt_defects_attempt_id` FOREIGN KEY (`attempt_id`) REFERENCES `test_attempts` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_attempt_defects_defect_id` FOREIGN KEY (`defect_id`) REFERENCES `defects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_test_attempt_defects_linked_by` FOREIGN KEY (`linked_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
