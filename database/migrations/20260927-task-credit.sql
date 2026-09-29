-- R5.2-B. Apply once after 20260926-collaboration.sql with a DDL-capable account.
-- MySQL DDL auto-commits: take a current backup and verify each statement before deployment.
ALTER TABLE `work_tasks`
  ADD COLUMN `reward_credit` BIGINT NOT NULL DEFAULT 0 AFTER `description`,
  ADD UNIQUE KEY `uq_work_tasks_id_project` (`id`, `project_id`),
  ADD CONSTRAINT `ck_work_tasks_reward` CHECK (`reward_credit` >= 0);

ALTER TABLE `work_task_events`
  DROP CHECK `ck_work_task_events_type`,
  ADD CONSTRAINT `ck_work_task_events_type` CHECK (`event_type` IN
    ('CREATED','STARTED','SUBMITTED','ACCEPTED','RETURNED','CANCELLED','REASSIGNED','HANDOFF_ACCEPTED'));

CREATE TABLE `task_handoff_offers` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `request_key` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `project_id` BIGINT NOT NULL,
  `task_id` BIGINT NOT NULL,
  `from_user_id` BIGINT NOT NULL,
  `to_user_id` BIGINT NOT NULL,
  `credit_amount` BIGINT NOT NULL,
  `note` VARCHAR(500) NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `resolved_at` DATETIME(6) NULL,
  `pending_task_id` BIGINT GENERATED ALWAYS AS (CASE WHEN `status` = 'PENDING' THEN `task_id` ELSE NULL END) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_handoff_request` (`request_key`),
  UNIQUE KEY `uq_handoff_id_project` (`id`, `project_id`),
  UNIQUE KEY `uq_handoff_pending_task` (`pending_task_id`),
  KEY `ix_handoff_task_status` (`task_id`, `status`, `id`),
  KEY `ix_handoff_recipient_status` (`to_user_id`, `status`, `id`),
  KEY `ix_handoff_project_task` (`project_id`, `task_id`),
  KEY `ix_handoff_project_from` (`project_id`, `from_user_id`),
  KEY `ix_handoff_project_to` (`project_id`, `to_user_id`),
  CONSTRAINT `fk_handoff_task` FOREIGN KEY (`task_id`, `project_id`) REFERENCES `work_tasks` (`id`, `project_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_handoff_from` FOREIGN KEY (`project_id`, `from_user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_handoff_to` FOREIGN KEY (`project_id`, `to_user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_handoff_amount` CHECK (`credit_amount` > 0),
  CONSTRAINT `ck_handoff_distinct_users` CHECK (`from_user_id` <> `to_user_id`),
  CONSTRAINT `ck_handoff_status` CHECK (`status` IN ('PENDING','ACCEPTED','DECLINED','CANCELLED')),
  CONSTRAINT `ck_handoff_resolution` CHECK ((`status` = 'PENDING' AND `resolved_at` IS NULL) OR (`status` <> 'PENDING' AND `resolved_at` IS NOT NULL))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `credit_transfers` (
  `id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `project_id` BIGINT NOT NULL,
  `sender_user_id` BIGINT NOT NULL,
  `recipient_user_id` BIGINT NOT NULL,
  `amount` BIGINT NOT NULL,
  `kind` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `offer_id` BIGINT NULL,
  `note` VARCHAR(500) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_credit_transfers_id_project` (`id`, `project_id`),
  UNIQUE KEY `uq_credit_transfers_offer_project` (`offer_id`, `project_id`),
  KEY `ix_credit_transfers_project_created` (`project_id`, `created_at`, `id`),
  KEY `ix_credit_transfers_sender_created` (`sender_user_id`, `created_at`, `id`),
  KEY `ix_credit_transfers_recipient_created` (`recipient_user_id`, `created_at`, `id`),
  KEY `ix_credit_transfers_project_sender` (`project_id`, `sender_user_id`),
  KEY `ix_credit_transfers_project_recipient` (`project_id`, `recipient_user_id`),
  CONSTRAINT `fk_credit_transfers_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_credit_transfers_sender` FOREIGN KEY (`project_id`, `sender_user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_credit_transfers_recipient` FOREIGN KEY (`project_id`, `recipient_user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_credit_transfers_offer` FOREIGN KEY (`offer_id`, `project_id`) REFERENCES `task_handoff_offers` (`id`, `project_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_credit_transfers_amount` CHECK (`amount` > 0),
  CONSTRAINT `ck_credit_transfers_distinct_users` CHECK (`sender_user_id` <> `recipient_user_id`),
  CONSTRAINT `ck_credit_transfers_kind` CHECK ((`kind` = 'PEER' AND `offer_id` IS NULL) OR (`kind` = 'HANDOFF' AND `offer_id` IS NOT NULL))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `contribution_events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `task_id` BIGINT NOT NULL,
  `points` INT NOT NULL,
  `event_type` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_contribution_task` (`task_id`),
  KEY `ix_contribution_project_created` (`project_id`, `created_at`, `user_id`),
  KEY `ix_contribution_user_created` (`user_id`, `created_at`, `id`),
  KEY `ix_contribution_project_user` (`project_id`, `user_id`),
  CONSTRAINT `fk_contribution_task` FOREIGN KEY (`task_id`, `project_id`) REFERENCES `work_tasks` (`id`, `project_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_contribution_member` FOREIGN KEY (`project_id`, `user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_contribution_points` CHECK (`points` = 1),
  CONSTRAINT `ck_contribution_type` CHECK (`event_type` = 'TASK_ACCEPTED')
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE `credit_transactions`
  DROP CHECK `ck_credit_transactions_type`,
  MODIFY COLUMN `type` VARCHAR(24) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ADD COLUMN `project_id` BIGINT NULL AFTER `batch_id`,
  ADD COLUMN `transfer_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER `project_id`,
  ADD COLUMN `task_id` BIGINT NULL AFTER `transfer_id`,
  ADD UNIQUE KEY `uq_credit_transfer_user` (`transfer_id`, `user_id`),
  ADD UNIQUE KEY `uq_credit_task_reward` (`task_id`, `type`),
  ADD KEY `ix_credit_project_created` (`project_id`, `created_at`, `id`),
  ADD CONSTRAINT `fk_credit_tx_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_credit_tx_transfer` FOREIGN KEY (`transfer_id`, `project_id`) REFERENCES `credit_transfers` (`id`, `project_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `fk_credit_tx_task` FOREIGN KEY (`task_id`, `project_id`) REFERENCES `work_tasks` (`id`, `project_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `ck_credit_transactions_type` CHECK (
    (`type` IN ('GRANT','RECLAIM') AND `project_id` IS NULL AND `transfer_id` IS NULL AND `task_id` IS NULL) OR
    (`type` IN ('PEER_TRANSFER_OUT','PEER_TRANSFER_IN','HANDOFF_OUT','HANDOFF_IN') AND `project_id` IS NOT NULL AND `transfer_id` IS NOT NULL AND `task_id` IS NULL) OR
    (`type` = 'TASK_REWARD' AND `project_id` IS NOT NULL AND `transfer_id` IS NULL AND `task_id` IS NOT NULL));
