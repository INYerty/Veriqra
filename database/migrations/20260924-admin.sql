-- Apply once to an existing Veriqra database after review and backup.
-- MySQL 8.0.46; select the intended database explicitly. No USE or IF NOT EXISTS.
-- No production execution is part of Phase 5 R5 development.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION time_zone = '+00:00';

-- Supports ordered locking for the last active ADMIN invariant.
ALTER TABLE `users` ADD KEY `ix_users_admin_lock` (`system_role`, `status`, `id`);

-- Phase 5 R5 additive Administration tables. The preceding 19 business tables retain their V1 meaning.
CREATE TABLE `credit_accounts` (
  `user_id` BIGINT NOT NULL,
  `balance` BIGINT NOT NULL DEFAULT 0,
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_credit_accounts_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_credit_accounts_balance` CHECK (`balance` >= 0)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `credit_transactions` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NOT NULL,
  `amount` BIGINT NOT NULL,
  `type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `actor_user_id` BIGINT NOT NULL,
  `reason` VARCHAR(500) NULL,
  `batch_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_credit_user_created` (`user_id`, `created_at`, `id`),
  KEY `ix_credit_actor` (`actor_user_id`),
  UNIQUE KEY `uq_credit_batch_user` (`batch_id`, `user_id`),
  KEY `ix_credit_type_created` (`type`, `created_at`),
  CONSTRAINT `fk_credit_transactions_account` FOREIGN KEY (`user_id`) REFERENCES `credit_accounts` (`user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_credit_transactions_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_credit_transactions_amount` CHECK (`amount` > 0),
  CONSTRAINT `ck_credit_transactions_type` CHECK (`type` IN ('GRANT','RECLAIM'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `login_events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NULL,
  `username_attempted` VARCHAR(64) NOT NULL,
  `ip_address` VARCHAR(45) NOT NULL,
  `user_agent` VARCHAR(512) NULL,
  `browser` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operating_system` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `device_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `result` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `failure_reason` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_login_created` (`created_at`, `id`),
  KEY `ix_login_user_created` (`user_id`, `created_at`),
  KEY `ix_login_username_created` (`username_attempted`, `created_at`),
  KEY `ix_login_ip_created` (`ip_address`, `created_at`),
  KEY `ix_login_result_created` (`result`, `created_at`),
  CONSTRAINT `fk_login_events_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_login_events_result` CHECK (`result` IN ('SUCCESS','FAILURE','RATE_LIMITED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `access_logs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `user_id` BIGINT NULL,
  `ip_address` VARCHAR(45) NOT NULL,
  `user_agent` VARCHAR(512) NULL,
  `browser` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `operating_system` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `device_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `http_method` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `request_path` VARCHAR(512) NOT NULL,
  `status_code` SMALLINT UNSIGNED NOT NULL,
  `request_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `duration_ms` INT UNSIGNED NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_access_created` (`created_at`, `id`),
  KEY `ix_access_user_created` (`user_id`, `created_at`),
  KEY `ix_access_ip_created` (`ip_address`, `created_at`),
  KEY `ix_access_status_created` (`status_code`, `created_at`),
  CONSTRAINT `fk_access_logs_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_access_status` CHECK (`status_code` BETWEEN 100 AND 599)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `audit_logs` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `actor_user_id` BIGINT NOT NULL,
  `action` VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `target_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `target_id` BIGINT NULL,
  `summary` VARCHAR(500) NOT NULL,
  `metadata_json` JSON NULL,
  `ip_address` VARCHAR(45) NOT NULL,
  `request_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_audit_created` (`created_at`, `id`),
  KEY `ix_audit_actor_created` (`actor_user_id`, `created_at`),
  KEY `ix_audit_action_created` (`action`, `created_at`),
  KEY `ix_audit_target` (`target_type`, `target_id`),
  CONSTRAINT `fk_audit_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_audit_summary` CHECK (CHAR_LENGTH(TRIM(`summary`)) > 0)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Initialize every pre-existing user. New users receive an account in the Admin User transaction.
INSERT INTO `credit_accounts` (`user_id`, `balance`)
SELECT `id`, 0 FROM `users`;
