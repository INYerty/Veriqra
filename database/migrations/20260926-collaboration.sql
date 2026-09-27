-- Apply once to an existing Veriqra database after review and backup.
-- MySQL 8.0.46; select the intended database explicitly. No USE or IF NOT EXISTS.
-- Do not apply to production as part of local development.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;
SET SESSION time_zone = '+00:00';

-- Project collaboration is additive. Platform ADMIN remains an account-administration role;
-- project and team authority is scoped to an ACTIVE project membership.
CREATE TABLE `project_managers` (
  `project_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `appointed_by` BIGINT NOT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `appointed_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`project_id`, `user_id`),
  KEY `ix_project_managers_user` (`user_id`, `status`, `project_id`),
  KEY `ix_project_managers_appointed_by` (`appointed_by`),
  CONSTRAINT `fk_project_managers_member` FOREIGN KEY (`project_id`, `user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_project_managers_appointed_by` FOREIGN KEY (`appointed_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_project_managers_status` CHECK (`status` IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `project_teams` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `name` VARCHAR(120) NOT NULL,
  `lead_user_id` BIGINT NOT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `created_by` BIGINT NOT NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_project_teams_project_name` (`project_id`, `name`),
  UNIQUE KEY `uq_project_teams_id_project` (`id`, `project_id`),
  KEY `ix_project_teams_lead` (`project_id`, `lead_user_id`),
  KEY `ix_project_teams_creator` (`created_by`),
  CONSTRAINT `fk_project_teams_project` FOREIGN KEY (`project_id`) REFERENCES `projects` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_project_teams_lead` FOREIGN KEY (`project_id`, `lead_user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_project_teams_creator` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_project_teams_name` CHECK (CHAR_LENGTH(TRIM(`name`)) > 0),
  CONSTRAINT `ck_project_teams_status` CHECK (`status` IN ('ACTIVE','ARCHIVED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `team_members` (
  `team_id` BIGINT NOT NULL,
  `project_id` BIGINT NOT NULL,
  `user_id` BIGINT NOT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ACTIVE',
  `joined_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`team_id`, `user_id`),
  KEY `ix_team_members_team_project` (`team_id`, `project_id`),
  KEY `ix_team_members_project_user` (`project_id`, `user_id`),
  CONSTRAINT `fk_team_members_team` FOREIGN KEY (`team_id`, `project_id`) REFERENCES `project_teams` (`id`, `project_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_team_members_project_member` FOREIGN KEY (`project_id`, `user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_team_members_status` CHECK (`status` IN ('ACTIVE','INACTIVE'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `work_tasks` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `project_id` BIGINT NOT NULL,
  `team_id` BIGINT NOT NULL,
  `title` VARCHAR(240) NOT NULL,
  `description` TEXT NULL,
  `assignee_user_id` BIGINT NOT NULL,
  `created_by` BIGINT NOT NULL,
  `status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'OPEN',
  `accepted_by` BIGINT NULL,
  `accepted_at` DATETIME(6) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `updated_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `lock_version` INT UNSIGNED NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `ix_work_tasks_team_status` (`team_id`, `status`, `id`),
  KEY `ix_work_tasks_project_status` (`project_id`, `status`, `id`),
  KEY `ix_work_tasks_assignee_status` (`assignee_user_id`, `status`, `id`),
  KEY `ix_work_tasks_created_by` (`created_by`),
  KEY `ix_work_tasks_accepted_by` (`accepted_by`),
  CONSTRAINT `fk_work_tasks_team` FOREIGN KEY (`team_id`, `project_id`) REFERENCES `project_teams` (`id`, `project_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_work_tasks_assignee` FOREIGN KEY (`project_id`, `assignee_user_id`) REFERENCES `project_members` (`project_id`, `user_id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_work_tasks_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_work_tasks_accepted_by` FOREIGN KEY (`accepted_by`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_work_tasks_title` CHECK (CHAR_LENGTH(TRIM(`title`)) > 0),
  CONSTRAINT `ck_work_tasks_status` CHECK (`status` IN ('OPEN','IN_PROGRESS','SUBMITTED','ACCEPTED','CANCELLED')),
  CONSTRAINT `ck_work_tasks_acceptance` CHECK ((`status` = 'ACCEPTED' AND `accepted_by` IS NOT NULL AND `accepted_at` IS NOT NULL) OR (`status` <> 'ACCEPTED' AND `accepted_by` IS NULL AND `accepted_at` IS NULL))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE `work_task_events` (
  `id` BIGINT NOT NULL AUTO_INCREMENT,
  `task_id` BIGINT NOT NULL,
  `actor_user_id` BIGINT NOT NULL,
  `event_type` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `from_status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL,
  `to_status` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `from_assignee_user_id` BIGINT NULL,
  `to_assignee_user_id` BIGINT NOT NULL,
  `note` VARCHAR(500) NULL,
  `created_at` DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_work_task_events_task` (`task_id`, `id`),
  KEY `ix_work_task_events_actor` (`actor_user_id`, `created_at`),
  CONSTRAINT `fk_work_task_events_task` FOREIGN KEY (`task_id`) REFERENCES `work_tasks` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_work_task_events_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `ck_work_task_events_type` CHECK (`event_type` IN ('CREATED','STARTED','SUBMITTED','ACCEPTED','RETURNED','CANCELLED','REASSIGNED')),
  CONSTRAINT `ck_work_task_events_from_status` CHECK (`from_status` IS NULL OR `from_status` IN ('OPEN','IN_PROGRESS','SUBMITTED','ACCEPTED','CANCELLED')),
  CONSTRAINT `ck_work_task_events_to_status` CHECK (`to_status` IN ('OPEN','IN_PROGRESS','SUBMITTED','ACCEPTED','CANCELLED'))
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
