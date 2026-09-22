# Veriqra V1 数据库设计（Freeze v1.0）

> Veriqra 更名说明：本文较早的测试数量、提交 ID、旧包名/上下文、WAR 文件名与校验值属于更名前历史证据；最新发布候选、兼容规则及 ROOT 部署请以 [VERIQRA-RENAME.md](VERIQRA-RENAME.md) 为准。数据库物理名称保持不变。

状态：**QATrack V1 Domain Model Freeze v1.0**，冻结并于 MySQL 8.0.46 实际落地验证。日期：2026-09-06。
本文件保留原文件名以兼容链接，内容已是冻结定义，不再是 20 表草案。
完整执行定义以 [schema.sql](../database/schema.sql) 为准；[领域模型](DOMAIN-MODEL.md)、[ER](V1-ER.mmd)、[冻结决策](DOMAIN-FREEZE-v1.0.md)、[实测报告](DATABASE-VALIDATION-v1.0.md) 同步维护。

## 1. 统一存储约定

- 19 表、154 字段；19 PK、40 FK、13 UNIQUE、51 个命名且 ENFORCED 的 CHECK；24 个普通索引。非 PK 索引 37，总索引 56。
- 全表 InnoDB、DYNAMIC；实测页大小 16384 字节。普通文本 utf8mb4_0900_ai_ci，登录名/项目键/状态/角色为 ascii_bin，外部身份及来源域为 utf8mb4_0900_bin。
- ID/FK 统一有符号 BIGINT，实体 id AUTO_INCREMENT；关联行以业务复合 PK 标识，不额外加空壳 id。
- 所有 FK 引用父表 PK，ON DELETE RESTRICT / ON UPDATE RESTRICT。可空引用不自动 SET NULL，不级联删除证据。
- 本机 MySQL 8.0.46 已实测 RESTRICT FK 列参与行内 CHECK 可创建并强制执行；来源字段组合与复核字段组合已由 CHECK 保证。不要把此结果推广为 CASCADE/SET NULL 与 CHECK 的任意组合都可用。
- CHECK 不做跨表查询；跨项目、角色、状态流、最少子行、不可变历史仍见第 5 节。NOT NULL 与 CHECK 共同使用，避免 SQL UNKNOWN 放过必须非空的条件。
- 时间 DATETIME(6)，会话 UTC；created_at 默认 CURRENT_TIMESTAMP(6)，updated_at 由写入口维护；lock_version 为乐观锁计数，不是内容历史版本。
- 可空字段 DEFAULT NULL；非空且无默认值的字段必须明确提供。状态用 VARCHAR + CHECK，映射同名 Java enum 是后续工作；不创建角色字典、统计字段或 JSON ID 列表。
- 选择方案 A：test_runs.project_id NOT NULL，test_plan_id 可空；test_run_projects 已移除。项目必有性由数据库保证，Plan 同项目仍由 Service 保证。

## 2. 表与字段

以下 19 表的字段、默认值、PK/FK/UNIQUE/CHECK 与已执行的 schema.sql 一致。所有 CHECK 在目标版本实测生效。

### 2.1 users

核心：登录身份及平台管理权限；角色业务范围由项目成员决定。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `username` | `VARCHAR(64)` | 是 | 无（必须提供） | ASCII，Service 规范化为小写；不可变，唯一登录名 |
| `display_name` | `VARCHAR(80)` | 是 | 无（必须提供） | 非空显示名 |
| `password_hash` | `VARCHAR(255)` | 是 | 无（必须提供） | 仅存含算法参数和盐的密码哈希编码；禁止明文 |
| `system_role` | `VARCHAR(16)` | 是 | 'USER' | ASCII 二进制比较；CHECK IN ('ADMIN','USER') |
| `status` | `VARCHAR(16)` | 是 | 'ACTIVE' | ASCII 二进制比较；CHECK IN ('ACTIVE','DISABLED') |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，更新成功后 +1；不是内容版本 |

- PK：`(id)`。
- FK：无。
- UNIQUE：`uq_users_username(username)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_users_username_nonblank`：`CHAR_LENGTH(TRIM(username)) > 0`。
  - `ck_users_display_name_nonblank`：`CHAR_LENGTH(TRIM(display_name)) > 0`。
  - `ck_users_password_hash_nonblank`：`CHAR_LENGTH(TRIM(password_hash)) > 0`。
  - `ck_users_system_role`：`system_role IN ('ADMIN','USER')`。
  - `ck_users_status`：`status IN ('ACTIVE','DISABLED')`。

- username、display_name、password_hash 的 CHECK 为去除首尾空白后长度大于 0；用户名字符集规则由 Service 校验。
- 禁用用户而不删除；USER 仅表示非平台管理员，不是新增的项目业务角色。

### 2.2 projects

核心：质量数据边界；保存项目标识与归档状态。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `project_key` | `VARCHAR(16)` | 是 | 无（必须提供） | ASCII 二进制；Service 限制大写字母开头、后续大写字母/数字；不可变 |
| `name` | `VARCHAR(160)` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(name)) > 0 |
| `description` | `TEXT` | 否 | NULL | 可选详情，不允许存关联 ID 列表 |
| `status` | `VARCHAR(16)` | 是 | 'ACTIVE' | ASCII 二进制比较；CHECK IN ('ACTIVE','ARCHIVED') |
| `created_by` | `BIGINT` | 是 | 无（必须提供） | 创建者 |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，更新成功后 +1；不是内容版本 |

- PK：`(id)`。
- FK：`created_by → users(id)`。
- UNIQUE：`uq_projects_key(project_key)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_projects_name_nonblank`：`CHAR_LENGTH(TRIM(name)) > 0`。
  - `ck_projects_status`：`status IN ('ACTIVE','ARCHIVED')`。

- 项目归档后禁止业务写入；恢复只能由 ADMIN 显式执行。
- 不保存覆盖率、用例数或未关闭缺陷数。

### 2.3 project_members

关联：User 与 Project 的 N:M 关系，每个成员在一个项目只有一个角色。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `project_id` | `BIGINT` | 是 | 无（必须提供） | 项目 |
| `user_id` | `BIGINT` | 是 | 无（必须提供） | 用户 |
| `project_role` | `VARCHAR(16)` | 是 | 无（必须提供） | ASCII 二进制比较；CHECK IN ('TESTER','DEVELOPER') |
| `status` | `VARCHAR(16)` | 是 | 'ACTIVE' | ASCII 二进制比较；CHECK IN ('ACTIVE','INACTIVE') |
| `joined_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，更新成功后 +1；不是内容版本 |

- PK：`(project_id, user_id)`。
- FK：`project_id → projects(id)`；`user_id → users(id)`。
- UNIQUE：无额外 UNIQUE。
- 命名 CHECK（对应真实 DDL）：
  - `ck_project_members_project_role`：`project_role IN ('TESTER','DEVELOPER')`。
  - `ck_project_members_status`：`status IN ('ACTIVE','INACTIVE')`。

- PK 阻止重复加入；加入旧成员时恢复原行，不能新增第二角色行。
- 离开项目改 INACTIVE；旧报告人和执行者引用仍保留。

### 2.4 project_counters

基础关联：项目与编号种类的计数器，安全分配业务序号。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `project_id` | `BIGINT` | 是 | 无（必须提供） | 项目 |
| `entity_type` | `VARCHAR(16)` | 是 | 无（必须提供） | ASCII 二进制比较；CHECK IN ('REQ','TC','PLAN','BUG') |
| `next_value` | `BIGINT` | 是 | 1 | CHECK next_value > 0；溢出时拒绝分配 |

- PK：`(project_id, entity_type)`。
- FK：`project_id → projects(id)`。
- UNIQUE：无额外 UNIQUE。
- 命名 CHECK（对应真实 DDL）：
  - `ck_project_counters_entity_type`：`entity_type IN ('REQ','TC','PLAN','BUG')`。
  - `ck_project_counters_next_value`：`next_value > 0`。

- 创建项目时初始化 4 行；FOR UPDATE 锁定对应行，分配和业务插入同一事务。
- next_value 是分配器状态，不是展示统计；禁止 MAX(key_no)+1 或回收已提交编号。

### 2.5 requirements

核心：当前需求定义。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `project_id` | `BIGINT` | 是 | 无（必须提供） | 所属项目，创建后不允许迁移 |
| `key_no` | `BIGINT` | 是 | 无（必须提供） | 项目内业务序号，CHECK key_no > 0；不可变 |
| `title` | `VARCHAR(240)` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(title)) > 0 |
| `description` | `TEXT` | 否 | NULL | 可选详情，不允许存关联 ID 列表 |
| `priority` | `VARCHAR(16)` | 是 | 'MEDIUM' | ASCII 二进制比较；CHECK IN ('LOW','MEDIUM','HIGH') |
| `status` | `VARCHAR(16)` | 是 | 'DRAFT' | ASCII 二进制比较；CHECK IN ('DRAFT','ACTIVE','ARCHIVED') |
| `created_by` | `BIGINT` | 是 | 无（必须提供） | 创建者 |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，更新成功后 +1；不是内容版本 |

- PK：`(id)`。
- FK：`project_id → projects(id)`；`created_by → users(id)`。
- UNIQUE：`uq_requirements_project_key(project_id, key_no)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_requirements_key_no`：`key_no > 0`。
  - `ck_requirements_title_nonblank`：`CHAR_LENGTH(TRIM(title)) > 0`。
  - `ck_requirements_priority`：`priority IN ('LOW','MEDIUM','HIGH')`。
  - `ck_requirements_status`：`status IN ('DRAFT','ACTIVE','ARCHIVED')`。

- 需求内容修改时，现存 CONFIRMED 关联改 NEEDS_REVIEW；不在 V1 保留需求全文历史。

### 2.6 test_cases

核心：当前可编辑用例定义，步骤单独规范化；执行采用独立快照。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `project_id` | `BIGINT` | 是 | 无（必须提供） | 所属项目，创建后不允许迁移 |
| `key_no` | `BIGINT` | 是 | 无（必须提供） | 项目内业务序号，CHECK key_no > 0；不可变 |
| `title` | `VARCHAR(240)` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(title)) > 0 |
| `description` | `TEXT` | 否 | NULL | 可选详情，不允许存关联 ID 列表 |
| `preconditions` | `TEXT` | 否 | NULL | 前置条件 |
| `priority` | `VARCHAR(16)` | 是 | 'MEDIUM' | ASCII 二进制比较；CHECK IN ('LOW','MEDIUM','HIGH') |
| `status` | `VARCHAR(16)` | 是 | 'DRAFT' | ASCII 二进制比较；CHECK IN ('DRAFT','READY','ARCHIVED') |
| `created_by` | `BIGINT` | 是 | 无（必须提供） | 创建者 |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，更新成功后 +1；不是内容版本 |

- PK：`(id)`。
- FK：`project_id → projects(id)`；`created_by → users(id)`。
- UNIQUE：`uq_test_cases_project_key(project_id, key_no)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_cases_key_no`：`key_no > 0`。
  - `ck_test_cases_title_nonblank`：`CHAR_LENGTH(TRIM(title)) > 0`。
  - `ck_test_cases_priority`：`priority IN ('LOW','MEDIUM','HIGH')`。
  - `ck_test_cases_status`：`status IN ('DRAFT','READY','ARCHIVED')`。

- READY 至少有一个合法步骤；修改内容先回 DRAFT 并使关联待复核，完成审阅后再 READY。已创建 Run 的快照不变。
- 一个逻辑用例允许 0..N 个独立自动化映射；不存在映射即未绑定自动化，不在本表保存自动化身份列。

### 2.7 test_automation_identities

外部身份：在一个项目内唯一标识某来源域的一项自动化实现；不存逻辑用例 FK。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键 |
| `project_id` | `BIGINT` | 是 | 无（必须提供） | 身份所属项目，不可变 |
| `source` | `VARCHAR(16)` | 是 | 'JUNIT' | ASCII 二进制；V1 CHECK source IN ('JUNIT')，以后显式扩展 |
| `namespace` | `VARCHAR(128)` | 是 | 无（必须提供） | utf8mb4_0900_bin；模块/套件来源域，非临时文件名 |
| `external_key` | `VARCHAR(512)` | 是 | 无（必须提供） | utf8mb4_0900_bin；单个实现标识，按版本化规则从 classname/name 构造 |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |

- PK：`(id)`。
- FK：`project_id → projects(id)`。
- UNIQUE：`uq_automation_identity(project_id, source, namespace, external_key)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_automation_identities_source`：`source IN ('JUNIT')`。
  - `ck_test_automation_identities_namespace_nonblank`：`CHAR_LENGTH(TRIM(namespace)) > 0`。
  - `ck_automation_external_key_nonempty`：`CHAR_LENGTH(external_key) > 0`。

- 完整复合唯一索引，不使用前缀 UNIQUE 或未处理碰撞的摘要替代。键字段最大 2584 字节；已在 16KB 页/DYNAMIC 的 MySQL 8.0.46 实际创建成功，并验证重复、大小写、尾部空格及超长拒绝。
- 身份的 project_id/source/namespace/external_key 创建后不可改；别名作为新的身份行，不能覆盖历史外部名称。不可变性由 Service 保证。
- V1 人工确认映射时连同绑定行原子创建；未知项只在导入预览中存在，不自动批量污染身份库。

### 2.8 test_automation_mappings

身份绑定：一个逻辑 TestCase 可绑定多个外部实现；每个外部身份在 V1 至多绑定一个逻辑用例。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键 |
| `automation_identity_id` | `BIGINT` | 是 | 无（必须提供） | 独立外部身份；创建后不变 |
| `test_case_id` | `BIGINT` | 是 | 无（必须提供） | 映射的逻辑用例 |
| `status` | `VARCHAR(16)` | 是 | 'ACTIVE' | ASCII 二进制；CHECK IN ('ACTIVE','INACTIVE') |
| `created_by` | `BIGINT` | 是 | 无（必须提供） | 确认绑定的用户 |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，非内容版本 |

- PK：`(id)`。
- FK：`automation_identity_id → test_automation_identities(id)`；`test_case_id → test_cases(id)`；`created_by → users(id)`。
- UNIQUE：`uq_automation_mapping_identity(automation_identity_id)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_automation_mappings_status`：`status IN ('ACTIVE','INACTIVE')`。

- 身份所属项目与 Case.project_id 必须相同，Service 在事务内检查。映射表不冗余存 project_id/source/namespace/external_key。
- 已被任何 Attempt 引用后，automation_identity_id 和 test_case_id 不可重绑；仅可停用。尚无引用时可在锁定/版本检查下纠正 test_case_id。
- 停用后历史 FK 继续保留，不能用删除重建绕过唯一约束；恢复复用原行。V1 不支持同一历史身份被重新指向另一个用例。
- 身份与绑定拆成两表是为了同时保持项目内唯一约束和 3NF；不引入可插拔映射引擎。

### 2.9 test_steps

从属：当前用例的有序步骤，支持逐步编辑和重排。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `test_case_id` | `BIGINT` | 是 | 无（必须提供） | 当前用例 |
| `step_order` | `SMALLINT UNSIGNED` | 是 | 无（必须提供） | CHECK step_order > 0 |
| `action` | `TEXT` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(action)) > 0 |
| `expected_result` | `TEXT` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(expected_result)) > 0 |

- PK：`(test_case_id, step_order)`。
- FK：`test_case_id → test_cases(id)`。
- UNIQUE：无额外 UNIQUE。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_steps_step_order`：`step_order > 0`。
  - `ck_test_steps_action_nonblank`：`CHAR_LENGTH(TRIM(action)) > 0`。
  - `ck_test_steps_expected_result_nonblank`：`CHAR_LENGTH(TRIM(expected_result)) > 0`。

- PK 保证同一用例步骤号唯一；1..N 连续性与至少一步由 Service 保证。
- 重排锁定父用例，在同一事务内替换全部步骤，不能直接互换两个受唯一约束的序号。

### 2.10 test_case_requirements

关联：需求和用例 N:M，记录当前复核状态并保留已移除关系的存在证据。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `requirement_id` | `BIGINT` | 是 | 无（必须提供） | 需求 |
| `test_case_id` | `BIGINT` | 是 | 无（必须提供） | 用例 |
| `status` | `VARCHAR(16)` | 是 | 'NEEDS_REVIEW' | ASCII 二进制比较；CHECK IN ('CONFIRMED','NEEDS_REVIEW','REMOVED') |
| `linked_by` | `BIGINT` | 是 | 无（必须提供） | 最初建立关系的用户 |
| `linked_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `reviewed_by` | `BIGINT` | 否 | NULL | 最近一次有效确认者 |
| `reviewed_at` | `DATETIME(6)` | 否 | NULL | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |

- PK：`(requirement_id, test_case_id)`。
- FK：`requirement_id → requirements(id)`；`test_case_id → test_cases(id)`；`linked_by → users(id)`；`reviewed_by → users(id)`。
- UNIQUE：无额外 UNIQUE。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_case_requirements_status`：`status IN ('CONFIRMED','NEEDS_REVIEW','REMOVED')`。
  - `ck_trace_review_shape`：`(status = 'CONFIRMED' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL) OR (status IN ('NEEDS_REVIEW','REMOVED') AND reviewed_by IS NULL AND reviewed_at IS NULL)`。

- 两端 project_id 必须一致，由 Service 锁定父记录后检查；普通 FK 只保证对象存在。
- CONFIRMED 要求 reviewed_by/reviewed_at 非空，其他合法状态均为空，由 ck_trace_review_shape 保证；复核人权限与复核内容仍由 Service 校验。
- 移除改 REMOVED，重新关联复用原行；保留首次 linked_at，但不是完整关系变更日志。

### 2.11 test_plans

核心：可复用测试范围，一个计划可创建多个 Run。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `project_id` | `BIGINT` | 是 | 无（必须提供） | 所属项目，创建后不允许迁移 |
| `key_no` | `BIGINT` | 是 | 无（必须提供） | 项目内业务序号，CHECK key_no > 0；不可变 |
| `name` | `VARCHAR(160)` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(name)) > 0 |
| `description` | `TEXT` | 否 | NULL | 可选详情，不允许存关联 ID 列表 |
| `status` | `VARCHAR(16)` | 是 | 'DRAFT' | ASCII 二进制比较；CHECK IN ('DRAFT','READY','ARCHIVED') |
| `created_by` | `BIGINT` | 是 | 无（必须提供） | 创建者 |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，更新成功后 +1；不是内容版本 |

- PK：`(id)`。
- FK：`project_id → projects(id)`；`created_by → users(id)`。
- UNIQUE：`uq_test_plans_project_key(project_id, key_no)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_plans_key_no`：`key_no > 0`。
  - `ck_test_plans_name_nonblank`：`CHAR_LENGTH(TRIM(name)) > 0`。
  - `ck_test_plans_status`：`status IN ('DRAFT','READY','ARCHIVED')`。

- READY 时至少包含一个 READY 用例；创建 Run 时重新检查，不把 READY 当永久保证。
- 修改成员范围先回 DRAFT；旧 Run 的独立用例清单和内容快照不跟随变化。

### 2.12 test_plan_cases

关联：计划和当前用例 N:M；只表达当前计划范围。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `test_plan_id` | `BIGINT` | 是 | 无（必须提供） | 计划 |
| `test_case_id` | `BIGINT` | 是 | 无（必须提供） | 用例 |
| `added_by` | `BIGINT` | 是 | 无（必须提供） | 操作者 |
| `added_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |

- PK：`(test_plan_id, test_case_id)`。
- FK：`test_plan_id → test_plans(id)`；`test_case_id → test_cases(id)`；`added_by → users(id)`。
- UNIQUE：无额外 UNIQUE。
- CHECK：无额外 CHECK。

- 同项目检查在 Service；移出计划可物理删除关联行，不影响已生成的 Run。
- V1 不要求自定义计划用例顺序，展示按用例 key_no 排序，因此不加多余 sort_order。

### 2.13 test_runs

核心：一次具体执行；自身保存必填 project_id，test_plan_id 为可选来源。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `project_id` | `BIGINT` | 是 | 无（必须提供） | Run 必填的独立项目归属，创建后不可变 |
| `test_plan_id` | `BIGINT` | 否 | NULL | 可选来源计划；计划生成时必填，Ad-hoc/JUnit/CI 可空；创建后不改 |
| `name` | `VARCHAR(160)` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(name)) > 0 |
| `environment` | `VARCHAR(160)` | 否 | NULL | 执行环境标签 |
| `build_version` | `VARCHAR(80)` | 否 | NULL | 被测版本标签；不据此推断版本历史 |
| `status` | `VARCHAR(16)` | 是 | 'IN_PROGRESS' | ASCII 二进制比较；CHECK IN ('IN_PROGRESS','COMPLETED','CANCELLED') |
| `ended_at` | `DATETIME(6)` | 否 | NULL | UTC |
| `created_by` | `BIGINT` | 是 | 无（必须提供） | 创建者 |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，更新成功后 +1；不是内容版本 |

- PK：`(id)`。
- FK：`project_id → projects(id)`；`test_plan_id → test_plans(id)`；`created_by → users(id)`。
- UNIQUE：无额外 UNIQUE。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_runs_name_nonblank`：`CHAR_LENGTH(TRIM(name)) > 0`。
  - `ck_test_runs_status`：`status IN ('IN_PROGRESS','COMPLETED','CANCELLED')`。
  - `ck_runs_ended_at`：`(status <> 'IN_PROGRESS' OR ended_at IS NULL) AND (status NOT IN ('COMPLETED','CANCELLED') OR (ended_at IS NOT NULL AND ended_at >= created_at))`。

- Service 保证非空结果清单、终态不可改，以及 COMPLETED 时没有 NOT_RUN。
- project_id 的 NOT NULL/FK 保证每个 Run 引用一个存在项目；有 Plan 时仍由 Service 检查 Plan.project_id = Run.project_id，项目及来源创建后不可迁移。
- 不保存 total/pass_count/pass_rate；PLAN/AD_HOC 由 test_plan_id 是否为空判断，MANUAL/JUNIT/MIXED 由尝试来源判断，两者正交。没有任何 Attempt 时来源尚未确定，不默认归为 MANUAL。

### 2.14 test_run_cases

核心执行项：唯一 Run×Case，保存该 Run 采纳的用例内容快照；当前状态由尝试推导。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `test_run_id` | `BIGINT` | 是 | 无（必须提供） | Run |
| `test_case_id` | `BIGINT` | 是 | 无（必须提供） | 稳定用例身份 |
| `snapshot_title` | `VARCHAR(240)` | 是 | 无（必须提供） | 执行范围生成时的标题，之后不可变 |
| `snapshot_description` | `TEXT` | 否 | NULL | 当时描述 |
| `snapshot_preconditions` | `TEXT` | 否 | NULL | 当时前置条件 |
| `snapshot_priority` | `VARCHAR(16)` | 是 | 无（必须提供） | ASCII 二进制比较；CHECK IN ('LOW','MEDIUM','HIGH') |
| `captured_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |

- PK：`(id)`。
- FK：`test_run_id → test_runs(id)`；`test_case_id → test_cases(id)`。
- UNIQUE：`uq_run_cases_run_case(test_run_id, test_case_id)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_run_cases_snapshot_title_nonblank`：`CHAR_LENGTH(TRIM(snapshot_title)) > 0`。
  - `ck_test_run_cases_snapshot_priority`：`snapshot_priority IN ('LOW','MEDIUM','HIGH')`。

- 不保存 current_status、latest_attempt_id，也不复制 project_id 或不变的业务编号。
- 快照含独立 test_run_case_steps；无尝试为 NOT_RUN，否则取最大 attempt_no 的 PASS/FAIL/BLOCKED/SKIPPED。
- 同 Run 重测始终用本快照；要采用编辑后的当前用例必须新建 Run。

### 2.15 test_run_case_steps

执行证据从属：Run 中某执行项的有序步骤快照，和当前 test_steps 独立。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `test_run_case_id` | `BIGINT` | 是 | 无（必须提供） | 执行项 |
| `step_order` | `SMALLINT UNSIGNED` | 是 | 无（必须提供） | CHECK step_order > 0 |
| `action` | `TEXT` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(action)) > 0 |
| `expected_result` | `TEXT` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(expected_result)) > 0 |

- PK：`(test_run_case_id, step_order)`。
- FK：`test_run_case_id → test_run_cases(id)`。
- UNIQUE：无额外 UNIQUE。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_run_case_steps_step_order`：`step_order > 0`。
  - `ck_test_run_case_steps_action_nonblank`：`CHAR_LENGTH(TRIM(action)) > 0`。
  - `ck_test_run_case_steps_expected_result_nonblank`：`CHAR_LENGTH(TRIM(expected_result)) > 0`。

- 创建 Run 时复制，快照及顺序之后均不可改，不引用当前 test_steps 的可变行。
- 这是历史执行事实，不是用例完整版本库；数据库中仍是逐步骤关系行。

### 2.16 test_imports

基础事件：一次成功 JUnit 导入批次，提供上传者、来源证据与幂等性。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `test_run_id` | `BIGINT` | 是 | 无（必须提供） | 目标 Run，可有多个批次 |
| `request_key` | `BINARY(16)` | 是 | 无（必须提供） | 客户端 UUID 幂等令牌，跨批次唯一 |
| `report_sha256` | `BINARY(32)` | 是 | 无（必须提供） | 原报告字节摘要，允许不同请求有相同摘要 |
| `source_namespace` | `VARCHAR(128)` | 是 | 无（必须提供） | utf8mb4_0900_bin；精确的测试模块/套件来源域，不使用临时文件名 |
| `original_filename` | `VARCHAR(255)` | 是 | 无（必须提供） | 只保留安全文件名，不保存客户端路径 |
| `imported_by` | `BIGINT` | 是 | 无（必须提供） | 上传及确认操作者 |
| `imported_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |

- PK：`(id)`。
- FK：`test_run_id → test_runs(id)`；`imported_by → users(id)`。
- UNIQUE：`uq_imports_request(request_key)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_imports_source_namespace_nonblank`：`CHAR_LENGTH(TRIM(source_namespace)) > 0`。
  - `ck_test_imports_original_filename_nonblank`：`CHAR_LENGTH(TRIM(original_filename)) > 0`。

- 失败导入整批回滚，不存伪成功批次；失败原因由应用日志/响应记录，不保留原 XML。
- source_namespace 与 original_filename 的 CHECK 为非空白；不存重复结果计数。
- 本批次只进入一个 Run，可只覆盖其中一部分执行项；未覆盖项仍 NOT_RUN。
- 新建导入 Run 可不选 Plan，必选项目；按已确认映射的逻辑用例去重后冻结清单/快照。已存在 Run 不能因导入扩展范围。

### 2.17 test_attempts

核心结果事实：执行项的每次不可变尝试，可为 PASS/FAIL/BLOCKED/SKIPPED；NOT_RUN 不入表。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `test_run_case_id` | `BIGINT` | 是 | 无（必须提供） | 执行项 |
| `attempt_no` | `INT UNSIGNED` | 是 | 无（必须提供） | CHECK attempt_no > 0；按提交次序分配 |
| `status` | `VARCHAR(16)` | 是 | 无（必须提供） | ASCII 二进制比较；CHECK IN ('PASS','FAIL','BLOCKED','SKIPPED') |
| `executed_by` | `BIGINT` | 否 | NULL | 人工执行者；自动化时未知，不能伪造为上传者 |
| `import_id` | `BIGINT` | 否 | NULL | 自动化批次；人工时 NULL |
| `automation_mapping_id` | `BIGINT` | 否 | NULL | 导入采用的绑定；人工为 NULL，历史来源通过不可变身份取得 |
| `executed_at` | `DATETIME(6)` | 否 | NULL | UTC |
| `recorded_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `duration_ms` | `BIGINT UNSIGNED` | 否 | NULL | 执行时长；未知为 NULL，不伪造为 0 |
| `comment` | `TEXT` | 否 | NULL | 人工备注或 JUnit skipped 原因 |
| `failure_message` | `TEXT` | 否 | NULL | 保留失败摘要/堆栈；JUnit error 与 failure 标签在摘要前缀区分 |
| `submission_key` | `BINARY(16)` | 是 | 无（必须提供） | 一次尝试的 UUID 幂等令牌 |

- PK：`(id)`。
- FK：`test_run_case_id → test_run_cases(id)`；`executed_by → users(id)`；`import_id → test_imports(id)`；`automation_mapping_id → test_automation_mappings(id)`。
- UNIQUE：`uq_attempts_run_case_no(test_run_case_id, attempt_no)`；`uq_attempts_submission(submission_key)`；`uq_attempts_import_run_case(import_id, test_run_case_id)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_test_attempts_attempt_no`：`attempt_no > 0`。
  - `ck_test_attempts_status`：`status IN ('PASS','FAIL','BLOCKED','SKIPPED')`。
  - `ck_attempts_failure_message`：`status = 'FAIL' OR failure_message IS NULL`。
  - `ck_attempts_source_shape`：`(import_id IS NULL AND automation_mapping_id IS NULL AND executed_by IS NOT NULL) OR (import_id IS NOT NULL AND automation_mapping_id IS NOT NULL AND executed_by IS NULL)`。

- DB CHECK ck_attempts_source_shape：人工 import_id/automation_mapping_id 为 NULL、executed_by 非空；自动化两项都非空且 executed_by 为 NULL。自动化操作者从 Batch.imported_by 推导。
- test_imports.test_run_id 与 test_run_cases.test_run_id 必须相同；状态与执行项同项目权限在事务内校验。
- NOT_RUN 是没有尝试的执行项状态，不生成伪执行尝试。尝试提交后禁止 UPDATE/DELETE；更正也追加一条说明充分的新尝试。
- Service：test_automation_mappings.test_case_id = test_run_cases.test_case_id；身份项目 = Run.project_id；身份 source='JUNIT' 且 namespace=test_imports.source_namespace。只接受 ACTIVE 映射。
- 同一 RunCase 的自动化尝试 V1 只能采用同一个 mapping；在父执行项锁内核对。多实现碰撞整批拒绝，不能用插入顺序决定哪个实现覆盖另一个。人工重测仍可追加。
- SKIPPED 为实际收到的跳过结果，原因存 comment；无 Attempt 才是 NOT_RUN。NOT_RUN 不能作为状态插入本表。

### 2.18 defects

核心：独立项目缺陷；可以手工报告，也可以关联一个或多个失败尝试。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `id` | `BIGINT` | 是 | AUTO_INCREMENT | 内部主键；不作为业务编号 |
| `project_id` | `BIGINT` | 是 | 无（必须提供） | 所属项目，创建后不允许迁移 |
| `key_no` | `BIGINT` | 是 | 无（必须提供） | 项目内业务序号，CHECK key_no > 0；不可变 |
| `title` | `VARCHAR(240)` | 是 | 无（必须提供） | CHECK CHAR_LENGTH(TRIM(title)) > 0 |
| `description` | `TEXT` | 否 | NULL | 可选详情，不允许存关联 ID 列表 |
| `severity` | `VARCHAR(16)` | 是 | 'MEDIUM' | ASCII 二进制比较；CHECK IN ('LOW','MEDIUM','HIGH','CRITICAL') |
| `priority` | `VARCHAR(16)` | 是 | 'MEDIUM' | ASCII 二进制比较；CHECK IN ('LOW','MEDIUM','HIGH') |
| `status` | `VARCHAR(16)` | 是 | 'OPEN' | ASCII 二进制比较；CHECK IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED') |
| `reporter_id` | `BIGINT` | 是 | 无（必须提供） | 报告者 |
| `assignee_id` | `BIGINT` | 否 | NULL | 当前负责人，可未分配 |
| `resolution_note` | `TEXT` | 否 | NULL | RESOLVED/CLOSED 的处理说明；重新打开时清空当前说明 |
| `created_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `updated_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |
| `lock_version` | `INT UNSIGNED` | 是 | 0 | 乐观锁计数，更新成功后 +1；不是内容版本 |

- PK：`(id)`。
- FK：`project_id → projects(id)`；`reporter_id → users(id)`；`assignee_id → users(id)`。
- UNIQUE：`uq_defects_project_key(project_id, key_no)`。
- 命名 CHECK（对应真实 DDL）：
  - `ck_defects_key_no`：`key_no > 0`。
  - `ck_defects_title_nonblank`：`CHAR_LENGTH(TRIM(title)) > 0`。
  - `ck_defects_severity`：`severity IN ('LOW','MEDIUM','HIGH','CRITICAL')`。
  - `ck_defects_priority`：`priority IN ('LOW','MEDIUM','HIGH')`。
  - `ck_defects_status`：`status IN ('OPEN','IN_PROGRESS','RESOLVED','CLOSED','REOPENED')`。
  - `ck_defects_resolution_note`：`status NOT IN ('RESOLVED','CLOSED') OR (resolution_note IS NOT NULL AND CHAR_LENGTH(TRIM(resolution_note)) > 0)`。

- 新分配/重开时，assignee 必须是本项目 ACTIVE 成员 DEVELOPER 且用户 ACTIVE，或有平台 ADMIN 权限的 ACTIVE 用户。已 CLOSED 缺陷可保留停用人员作为历史负责人；状态流转、转派和成员退出协调由 Service 保证。
- 不保存 test_run_case_id 单值外键，不自动因为某次 PASS 关闭缺陷。

### 2.19 test_attempt_defects

关联：失败尝试与缺陷 N:M，让缺陷证据固定到实际 FAIL 记录。

| Column | Data Type | NOT NULL | DEFAULT | 含义 / CHECK |
| --- | --- | --- | --- | --- |
| `attempt_id` | `BIGINT` | 是 | 无（必须提供） | 失败尝试 |
| `defect_id` | `BIGINT` | 是 | 无（必须提供） | 缺陷 |
| `linked_by` | `BIGINT` | 是 | 无（必须提供） | 建立关联的用户 |
| `linked_at` | `DATETIME(6)` | 是 | CURRENT_TIMESTAMP(6) | UTC |

- PK：`(attempt_id, defect_id)`。
- FK：`attempt_id → test_attempts(id)`；`defect_id → defects(id)`；`linked_by → users(id)`。
- UNIQUE：无额外 UNIQUE。
- CHECK：无额外 CHECK。

- 新建关联必须指向 FAIL 且两端同项目，Service 检查；数据库 FK 本身不检查状态或项目。
- 可以显式纠正错误关联并删除该关系行，不能删除尝试或缺陷；V1 不提供关联操作完整审计。

## 3. Index Plan

实测 37 个非 PK 索引：13 UNIQUE + 24 普通索引；加上 19 PK 共 56 个。SHOW INDEX/INFORMATION_SCHEMA 未发现额外自动 FK 索引。
40 个 FK 的左前缀由 7 PK、9 UNIQUE、24 普通索引支持。唯一键本身就是索引，不能重复计数或为其另建单列索引。
本轮仅将原 RunProject 的项目索引替换为 test_runs 项目/创建时间/id 索引，支持已有项目 Run 列表。
上轮暂缓的 requirements / defects 项目状态索引仍不创建，相关查询先按项目范围过滤。

| Table / Index definition | 典型查询 / 完整性操作 | 字段顺序及避免重复的理由 |
| --- | --- | --- |
| `users`: UNIQUE `uq_users_username(username)` | `WHERE username=?` | 登录精确定位并阻止创建重名用户 |
| `projects`: UNIQUE `uq_projects_key(project_key)` | `WHERE project_key=?` | 业务 URL/全局项目键唯一定位 |
| `projects`: INDEX `ix_projects_created_by(created_by)` | `WHERE created_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `project_members`: INDEX `ix_members_user_status_project(user_id, status, project_id)` | `WHERE user_id=? AND status='ACTIVE'` | My Projects；用户等值在前、成员状态过滤在后；覆盖 user_id FK |
| `requirements`: UNIQUE `uq_requirements_project_key(project_id, key_no)` | `WHERE project_id=? AND key_no=?；或 WHERE project_id=? ORDER BY key_no` | 项目为隔离边界在前，序号在后；同时覆盖 project_id FK |
| `requirements`: INDEX `ix_requirements_created_by(created_by)` | `WHERE created_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_cases`: UNIQUE `uq_test_cases_project_key(project_id, key_no)` | `WHERE project_id=? AND key_no=?；或 WHERE project_id=? ORDER BY key_no` | 项目为隔离边界在前，序号在后；同时覆盖 project_id FK |
| `test_cases`: INDEX `ix_test_cases_created_by(created_by)` | `WHERE created_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_automation_identities`: UNIQUE `uq_automation_identity(project_id, source, namespace, external_key)` | `WHERE project_id=? AND source=? AND namespace=? AND external_key=?` | 同项目自动化身份唯一解析；项目前缀同时覆盖 FK；允许不同项目复用同样外部名称 |
| `test_automation_mappings`: UNIQUE `uq_automation_mapping_identity(automation_identity_id)` | `WHERE automation_identity_id=?` | 一个外部身份至多映射一个用例；同时覆盖 identity FK |
| `test_automation_mappings`: INDEX `ix_automation_mappings_case_status(test_case_id, status, id)` | `WHERE test_case_id=? AND status='ACTIVE'` | 列出用例的多个自动化实现；覆盖 Case FK |
| `test_automation_mappings`: INDEX `ix_automation_mappings_created_by(created_by)` | `WHERE created_by=?` | 确认人引用的反向定位与 FK 支持 |
| `test_case_requirements`: INDEX `ix_trace_case_requirement(test_case_id, requirement_id)` | `WHERE test_case_id=?` | 用例反查需求及 FK；PK 已覆盖需求正查，不另加 requirement_id 索引 |
| `test_case_requirements`: INDEX `ix_test_case_requirements_linked_by(linked_by)` | `WHERE linked_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_case_requirements`: INDEX `ix_test_case_requirements_reviewed_by(reviewed_by)` | `WHERE reviewed_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_plans`: UNIQUE `uq_test_plans_project_key(project_id, key_no)` | `WHERE project_id=? AND key_no=?；或 WHERE project_id=? ORDER BY key_no` | 项目为隔离边界在前，序号在后；同时覆盖 project_id FK |
| `test_plans`: INDEX `ix_test_plans_created_by(created_by)` | `WHERE created_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_plan_cases`: INDEX `ix_plan_cases_case_plan(test_case_id, test_plan_id)` | `WHERE test_case_id=?` | 用例反查受影响计划及 FK；PK 覆盖计划正查 |
| `test_plan_cases`: INDEX `ix_test_plan_cases_added_by(added_by)` | `WHERE added_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_runs`: INDEX `ix_runs_plan_created_id(test_plan_id, created_at, id)` | `WHERE test_plan_id=? ORDER BY created_at DESC,id DESC` | 计划等值在前，时间和稳定分页 id 在后；覆盖计划 FK |
| `test_runs`: INDEX `ix_test_runs_created_by(created_by)` | `WHERE created_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_runs`: INDEX `ix_runs_project_created_id(project_id, created_at, id)` | `WHERE project_id=? ORDER BY created_at DESC,id DESC` | 项目执行列表；项目等值、时间与稳定分页键在后；覆盖 project_id FK |
| `test_run_cases`: UNIQUE `uq_run_cases_run_case(test_run_id, test_case_id)` | `WHERE test_run_id=?；WHERE test_run_id=? AND test_case_id=?` | 唯一执行项，防重复生成；覆盖 Run FK |
| `test_run_cases`: INDEX `ix_run_cases_case_run(test_case_id, test_run_id)` | `WHERE test_case_id=?` | 用例执行历史与缺陷追踪；覆盖 Case FK |
| `test_imports`: UNIQUE `uq_imports_request(request_key)` | `WHERE request_key=?` | 重复请求返回已提交批次；同令牌不同目标/摘要/来源拒绝 |
| `test_imports`: INDEX `ix_imports_run_time_id(test_run_id, imported_at, id)` | `WHERE test_run_id=? ORDER BY imported_at,id` | 查看 Run 导入批次；覆盖 Run FK |
| `test_imports`: INDEX `ix_test_imports_imported_by(imported_by)` | `WHERE imported_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_attempts`: UNIQUE `uq_attempts_run_case_no(test_run_case_id, attempt_no)` | `WHERE test_run_case_id=? ORDER BY attempt_no DESC LIMIT 1` | 保证次序唯一，反向扫描取当前结果；覆盖执行项 FK |
| `test_attempts`: UNIQUE `uq_attempts_submission(submission_key)` | `WHERE submission_key=?` | 重复提交同一尝试不产生新记录 |
| `test_attempts`: UNIQUE `uq_attempts_import_run_case(import_id, test_run_case_id)` | `WHERE import_id=?` | 每批次每执行项最多一条；覆盖导入 FK；NULL 导入允许人工多次尝试 |
| `test_attempts`: INDEX `ix_test_attempts_executed_by(executed_by)` | `WHERE executed_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_attempts`: INDEX `ix_attempts_automation_mapping(automation_mapping_id)` | `WHERE automation_mapping_id=?` | 自动化实现执行历史、检查是否已使用以禁止重绑；覆盖 mapping FK |
| `defects`: UNIQUE `uq_defects_project_key(project_id, key_no)` | `WHERE project_id=? AND key_no=?；或 WHERE project_id=? ORDER BY key_no` | 项目为隔离边界在前，序号在后；同时覆盖 project_id FK |
| `defects`: INDEX `ix_defects_assignee_status_id(assignee_id, status, id)` | `WHERE assignee_id=? AND status=?` | 我的待处理缺陷；覆盖 assignee FK；按项目筛选时残余过滤 |
| `defects`: INDEX `ix_defects_reporter_id(reporter_id)` | `WHERE reporter_id = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |
| `test_attempt_defects`: INDEX `ix_attempt_defects_defect_attempt(defect_id, attempt_id)` | `WHERE defect_id=?` | 缺陷反查全部失败证据；覆盖 defect FK |
| `test_attempt_defects`: INDEX `ix_test_attempt_defects_linked_by(linked_by)` | `WHERE linked_by = ?` | 用户引用的反向定位及 FK 子表索引；仅单列，不虚构 UI 功能 |

复合 PK 保留成员、编号、当前步骤、覆盖关系、计划清单、快照步骤与失败证据的正向索引；三个 N:M 反向索引不能被 PK 第二列替代。
二级索引隐含 PK；显式末尾 id/PK 分量仅保留排序和覆盖意图，不另建短索引。
未添加单独 status/priority/severity、TEXT 普通索引或无 V1 依据的性能索引。
已对项目 Run 列表和最新 Attempt 查询执行 EXPLAIN ANALYZE，结果见实测文件；680 行样本不构成大规模性能证明。

## 4. 核心查询与指标

可直接执行的 10 组查询及 2 个执行计划见 [queries.sql](../database/queries.sql)，已全部运行成功。

- Coverage：ACTIVE 需求为分母；存在同项目 READY Case + CONFIRMED 链接才计分子；每需求最多计一次。
- 无任何用例：没有非 REMOVED 链接；无有效覆盖：没有合格 CONFIRMED/READY 链接，两者分别查询。
- Current Outcome：按 RunCase 最大 attempt_no，没行才是 NOT_RUN；不用 MAX(id) 或 executed_at 判当前。
- N=执行项数，P/F/B/S=各最新结果数，R=P+F+B+S；NOT_RUN=N−R，实际执行数=P+F。
- Pass Rate=P/(P+F)，实际执行率=(P+F)/N，记录完成率=R/N；分母 0 返回 SQL NULL，页面/报告显示 N/A。
- COMPLETED 要求 N>0 且 R=N；全 SKIPPED 可完成，但实际执行数 0、通过率 N/A。BLOCKED 与 SKIPPED 分列。
- 需求到缺陷沿 Requirement→Link→Case→RunCase→Attempt→Evidence→Defect 查询；FAIL→PASS 后旧失败证据仍保留。
- 项目执行指标选择单个 Run 或按 Run 分组，不能将不同 Run 的同一逻辑用例混计。

## 5. 规范化与完整性边界

### 5.1 Run 归属取舍

最终采用方案 A。Project 是 Run 自身必需归属，Plan 是可选来源；直接 NOT NULL/FK 比一对一表更简单且能阻止无项目 Run。
非空 Plan 子集存在 Plan→Project 的条件业务依赖，本模型接受这处有意冗余，不伪称所有带 NULL 的关系都有经典严格 3NF 证明。
方案 B 单表依赖较简单，却没有消除跨表同项目规则，还增加每 Run 至少一条归属的 Service 缺口；详见 [七维比较](DOMAIN-FREEZE-v1.0.md)。

其他关系保持明确的候选键与职责：成员角色依赖 Project×User；步骤依赖父对象×序号；N:M 用关联表；
快照依赖 Run×Case，不依赖当前 Case 全文；Attempt 依赖执行项×尝试序号；外部身份项目内唯一，Mapping 不复制由 Case 推导的 project_id。
不复制用户名称、外部身份文本、当前结果或统计百分比。

### 5.2 数据库已经保证

| 规则 | 已执行的数据库约束 |
| --- | --- |
| 每 Run 一个存在项目 | test_runs.project_id NOT NULL + FK |
| Plan 可空、对象存在 | 可空 test_plan_id FK |
| 业务编号/身份/关系唯一 | 19 PK 和 13 UNIQUE；NULL import 允许人工多次尝试 |
| 合法状态、正序号、非空文本、结束时间、处理说明 | 51 个命名 CHECK 中对应规则 |
| 人工/自动化来源组合 | ck_attempts_source_shape；RESTRICT FK 列参与 CHECK 已实测 |
| CONFIRMED 复核字段组合 | ck_trace_review_shape；其他合法状态字段为空 |
| 引用父键删除/更新限制 | 40 FK，全部 DELETE/UPDATE RESTRICT |

### 5.3 仍属于 Service invariant

- Requirement–Case、Plan–Case、Run–Plan、RunCase–Case、Identity–Case、Attempt–Defect 的项目一致性。
- Attempt 与 Import 同 Run、与 Mapping 同 Case，以及 source/namespace 匹配；同一 RunCase 只用一个自动化实现。
- 操作用户/成员/负责人状态与角色；ADMIN 不必有项目成员行，不能直接用成员复合 FK 替代当前权限规则。
- Run/Plan/READY Case 的非空子项、步骤连续、父状态、完整快照及原子导入。
- Run 项目/来源、已使用绑定、快照和 Attempt 不可覆盖；RESTRICT 不阻止直接更新子表 FK 或没有下游引用的叶子行。
- 只有 FAIL 可关联缺陷；终态 Run 不接收新尝试；旧报告按接受次序影响当前结果；并发锁定、版本比较、编号分配及幂等载荷校验。
- 归档和角色变化协调、覆盖复核内容有效性、AI 预览版本校验等跨表业务规则。

约束测试实际证明了跨项目等写入可以通过数据库，随后均回滚；测试中的“预期接受”用于暴露边界，不代表业务允许。
可通过新增冗余 project_id/复合 FK 或触发器加强部分规则，但本次冻结未采用，不把它们算成已受数据库保护。
[service-invariants.sql](../database/service-invariants.sql) 只检查测试数据一致性，不安装触发器，也不保证未来写入。

## 6. 实际验证

MySQL 8.0.46 独立实例已从空库执行 schema.sql、seed.sql、constraint-tests.sql、queries.sql、service-invariants.sql、inspect.sql。
178 项约束/数据测试通过，22 项数据业务一致性检查零违规，19 表各至少 10 行、合计 680 行。
最终实例对象和完整证据见 [DATABASE-VALIDATION-v1.0.md](DATABASE-VALIDATION-v1.0.md) 与 [database/verification](../database/verification)。
尚无 Java、Servlet、DAO、Service 或前端实现；SQL 回滚测试不等于 Service 并发集成测试或课程完整性能验收。

## 7. MySQL 依据

- [CHECK constraints](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)
- [Foreign keys / referential actions](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)
- [Index Extensions](https://dev.mysql.com/doc/refman/8.0/en/index-extensions.html)

依据文档明确限制，最终以本轮 MySQL 8.0.46 的 CREATE、非法写入、INFORMATION_SCHEMA 与执行计划证据核验；不只按文档推测。
