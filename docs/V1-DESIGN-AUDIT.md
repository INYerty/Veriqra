# QATrack V1 设计收尾审计（冻结前历史记录）

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

> 本文保留当时 20 表模型的审计经过，不代表当前结构。后续已宣布 [Freeze v1.0](DOMAIN-FREEZE-v1.0.md)，采用 Run 直接保存 project_id，最终为 19 表、154 字段、40 FK。当前依据见 [数据库设计](DATABASE-DESIGN-DRAFT.md) 和 [实测报告](DATABASE-VALIDATION-v1.0.md)。

日期：2026-09-06。依据：重新读取 <PROJECT_ROOT> 工作区当前的 [领域模型](DOMAIN-MODEL.md)、[数据库草案](DATABASE-DESIGN-DRAFT.md) 和 [ER](V1-ER.mmd)，逐项核对后修正文档。
范围仅为设计收尾：不增加表、字段或产品功能，不实现 Java、不创建正式 DDL、不连接数据库、不 commit/push。

## 1. 最终规模与表职责

| 指标 | 本轮读入 | 收尾后 |
| --- | --- | --- |
| 表 | 20 | **20** |
| 字段（每个表字段计一次） | 155 | **155** |
| 外键约束 | 41 | **41** |
| 非主键索引 | 39 | **37** |
| 其中 UNIQUE / 普通索引 | 13 / 26 | **13 / 24** |
| 主键索引（不计入上项） | 20 | **20** |
| ER 中显示的外键边 | 27 | **41** |

37 是设计计划数，并非已建数据库的 SHOW INDEX 实测数；包括 UNIQUE 自带索引，每个只计一次。加上 20 个 PK，总计划索引数为 57。

| 表 | 职责 | 字段数 | FK 数 | 非 PK 索引数 |
| --- | --- | --- | --- | --- |
| `users` | 登录身份、平台 ADMIN/USER 及稳定操作者引用 | 9 | 0 | 1 |
| `projects` | 项目标识、质量数据边界与归档状态 | 9 | 1 | 2 |
| `project_members` | 用户在项目中的 TESTER/DEVELOPER 身份与有效性 | 7 | 2 | 1 |
| `project_counters` | 原子分配项目内 REQ/TC/PLAN/BUG 业务编号 | 3 | 1 | 0 |
| `requirements` | 当前需求内容、优先级和生命周期 | 11 | 2 | 2 |
| `test_cases` | 当前可编辑逻辑用例定义 | 12 | 2 | 2 |
| `test_automation_identities` | 项目范围内唯一的外部自动化身份 | 6 | 1 | 1 |
| `test_automation_mappings` | 外部身份到逻辑用例的绑定，一个用例可有多个实现 | 8 | 3 | 3 |
| `test_steps` | 当前用例的结构化有序步骤 | 4 | 1 | 0 |
| `test_case_requirements` | 需求与用例的 N:M 覆盖关系及复核状态 | 8 | 4 | 3 |
| `test_plans` | 可复用测试范围及计划状态 | 10 | 2 | 2 |
| `test_plan_cases` | 计划当前包含的用例集合 | 4 | 3 | 2 |
| `test_runs` | 一次执行上下文、可选来源 Plan 及生命周期 | 11 | 2 | 2 |
| `test_run_projects` | Run 必填且唯一的独立项目归属 | 2 | 2 | 1 |
| `test_run_cases` | Run×Case 执行项及不可变内容快照 | 8 | 2 | 2 |
| `test_run_case_steps` | 执行项的不可变有序步骤快照 | 4 | 1 | 0 |
| `test_imports` | 成功导入批次的来源、摘要、操作者及幂等信息 | 8 | 2 | 3 |
| `test_attempts` | 每次不可变执行结果、次序、来源及幂等信息 | 13 | 4 | 5 |
| `defects` | 项目缺陷及当前指派、处理状态 | 14 | 3 | 3 |
| `test_attempt_defects` | 具体 FAIL 尝试与缺陷的 N:M 证据关系 | 4 | 3 | 2 |
| **合计** | | **155** | **41** | **37** |

## 2. 与上一版 17 表相比的 3 张新增表

此处比较的是此前 17 表模型与本轮开始时已经存在的 20 表模型；本轮审计未再新增表。
执行项、步骤快照、Attempt 的三处重命名不计为新增实体。

| 新增表 | 在已选模型中的必要职责 | 去掉而不重设计的后果 |
| --- | --- | --- |
| `test_run_projects` | 给无 Plan 和有 Plan 的 Run 提供同一种必填项目归属，保持当前 test_runs 不重复存项目事实的取舍 | 无 Plan 的 Run 将无法可靠确定所属项目；不能仅从可空 Plan 推导权限 |
| `test_automation_identities` | 用项目/source/namespace/external_key 唯一键保存稳定外部身份；不把 Case 决定的 project_id 冗余写进绑定表 | 仅凭当前 mappings 列无法对项目内外部身份建立单表 UNIQUE，或被迫把身份/项目事实重新塞入绑定表 |
| `test_automation_mappings` | 分离逻辑 Case 与实现身份，Case 可一对多；Attempt 引用实际使用的绑定 | 回到 Case 单个 automation_key 会丢失多实现能力；Attempt 无法沿当前关系保存采用的绑定 |

“必须”是相对于当前已选字段布局、项目内身份唯一性和用户的一对多要求而言，并非声称关系数据库只有这一种实现。
Run 项目字段直接放回 test_runs、或合并身份和绑定，也能形成其他取舍；那需要重开规范化与约束决策，不属于本轮收尾。
特别是 nullable Plan 的项目依赖属于非空子集上的业务依赖，不能仅据此宣称任何同表设计必然违反经典 3NF。
保留这三个表的成本是额外 JOIN、Service 原子创建及跨表一致性校验；没有借此引入 CI 调度、动态映射引擎或版本功能。

## 3. 39 个非主键索引逐项审查

### 3.1 结论与删除理由

- 原计划没有完全重复的索引定义；13 个 UNIQUE 各只计一条，没有同时另建同列普通索引。
- 没有可被另一完整左前缀替代的单列索引；三个 N:M 反向索引仍有必要，复合 PK 的第二列不能单独充当 FK 支持前缀。
- 删除 `ix_requirements_project_status_id(project_id,status,id)` 与 `ix_defects_project_status_id(project_id,status,id)`。两者有当前 V1 查询依据，不是虚构未来功能；但并非完整性必需，且没有数据规模或执行计划证据表明现在需要额外的状态访问路径。
- 对应查询改以各自 `UNIQUE(project_id,key_no)` 的左前缀限定项目，再过滤状态。查询功能与统计定义不变；状态过滤可能回表、分页可能排序，不宣称删除后性能更好。
- 保留的 24 个普通索引各承担一条未被 PK/UNIQUE 支持的 FK；没有为作者、复核人等引用杜撰统计页面，也不能为减少计数直接删除其必要索引。
- 41 条 FK 的支持分配：**8 PK + 9 UNIQUE + 24 普通索引**。建表时使用最终索引计划，不另为 FK 生成同前缀单列索引；随后以 SHOW INDEX 验证实际结果。[MySQL FK 索引规则](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)
- 二级索引隐含 PK。原定义中的末尾 id、N:M 反向索引中的另一个 PK 分量保留作排序/覆盖意图说明，不再配套建短索引；这些尾列不代表存在第二棵索引，不能当作删掉一条索引来统计。[MySQL Index Extensions](https://dev.mysql.com/doc/refman/8.0/en/index-extensions.html)

因此本轮是 **39 → 37，暂缓两条可选性能索引，重复索引数为 0**，并非发现了两条完全相同的索引。尚未运行 EXPLAIN ANALYZE；以后只按真实查询与代表数据调整。

### 3.2 原计划的逐项处置

下列编号仅供审计对照。保留项的完整字段顺序和典型查询仍以数据库草案第 3 节为准，避免维护第二份独立索引定义。

| 原序号 | 表 / 索引 | 处置及依据 |
| --- | --- | --- |
| 1 | `users.uq_users_username` | 保留；业务唯一性 / 幂等性 |
| 2 | `projects.uq_projects_key` | 保留；业务唯一性 / 幂等性 |
| 3 | `projects.ix_projects_created_by` | 保留；FK `created_by` 的必要左前缀，兼顾现有查询 |
| 4 | `project_members.ix_members_user_status_project` | 保留；FK `user_id` 的必要左前缀，兼顾现有查询 |
| 5 | `requirements.uq_requirements_project_key` | 保留；业务唯一性，同时支持 FK `project_id` |
| 6 | `requirements.ix_requirements_project_status_id` | 暂缓删除；项目 FK 已由业务编号 UNIQUE 支持，状态先残余过滤 |
| 7 | `requirements.ix_requirements_created_by` | 保留；FK `created_by` 的必要左前缀，兼顾现有查询 |
| 8 | `test_cases.uq_test_cases_project_key` | 保留；业务唯一性，同时支持 FK `project_id` |
| 9 | `test_cases.ix_test_cases_created_by` | 保留；FK `created_by` 的必要左前缀，兼顾现有查询 |
| 10 | `test_automation_identities.uq_automation_identity` | 保留；业务唯一性，同时支持 FK `project_id` |
| 11 | `test_automation_mappings.uq_automation_mapping_identity` | 保留；业务唯一性，同时支持 FK `automation_identity_id` |
| 12 | `test_automation_mappings.ix_automation_mappings_case_status` | 保留；FK `test_case_id` 的必要左前缀，兼顾现有查询 |
| 13 | `test_automation_mappings.ix_automation_mappings_created_by` | 保留；FK `created_by` 的必要左前缀，兼顾现有查询 |
| 14 | `test_case_requirements.ix_trace_case_requirement` | 保留；FK `test_case_id` 的必要左前缀，兼顾现有查询 |
| 15 | `test_case_requirements.ix_test_case_requirements_linked_by` | 保留；FK `linked_by` 的必要左前缀，兼顾现有查询 |
| 16 | `test_case_requirements.ix_test_case_requirements_reviewed_by` | 保留；FK `reviewed_by` 的必要左前缀，兼顾现有查询 |
| 17 | `test_plans.uq_test_plans_project_key` | 保留；业务唯一性，同时支持 FK `project_id` |
| 18 | `test_plans.ix_test_plans_created_by` | 保留；FK `created_by` 的必要左前缀，兼顾现有查询 |
| 19 | `test_plan_cases.ix_plan_cases_case_plan` | 保留；FK `test_case_id` 的必要左前缀，兼顾现有查询 |
| 20 | `test_plan_cases.ix_test_plan_cases_added_by` | 保留；FK `added_by` 的必要左前缀，兼顾现有查询 |
| 21 | `test_runs.ix_runs_plan_created_id` | 保留；FK `test_plan_id` 的必要左前缀，兼顾现有查询 |
| 22 | `test_runs.ix_test_runs_created_by` | 保留；FK `created_by` 的必要左前缀，兼顾现有查询 |
| 23 | `test_run_projects.ix_run_projects_project_run` | 保留；FK `project_id` 的必要左前缀，兼顾现有查询 |
| 24 | `test_run_cases.uq_run_cases_run_case` | 保留；业务唯一性，同时支持 FK `test_run_id` |
| 25 | `test_run_cases.ix_run_cases_case_run` | 保留；FK `test_case_id` 的必要左前缀，兼顾现有查询 |
| 26 | `test_imports.uq_imports_request` | 保留；业务唯一性 / 幂等性 |
| 27 | `test_imports.ix_imports_run_time_id` | 保留；FK `test_run_id` 的必要左前缀，兼顾现有查询 |
| 28 | `test_imports.ix_test_imports_imported_by` | 保留；FK `imported_by` 的必要左前缀，兼顾现有查询 |
| 29 | `test_attempts.uq_attempts_run_case_no` | 保留；业务唯一性，同时支持 FK `test_run_case_id` |
| 30 | `test_attempts.uq_attempts_submission` | 保留；业务唯一性 / 幂等性 |
| 31 | `test_attempts.uq_attempts_import_run_case` | 保留；业务唯一性，同时支持 FK `import_id` |
| 32 | `test_attempts.ix_test_attempts_executed_by` | 保留；FK `executed_by` 的必要左前缀，兼顾现有查询 |
| 33 | `test_attempts.ix_attempts_automation_mapping` | 保留；FK `automation_mapping_id` 的必要左前缀，兼顾现有查询 |
| 34 | `defects.uq_defects_project_key` | 保留；业务唯一性，同时支持 FK `project_id` |
| 35 | `defects.ix_defects_project_status_id` | 暂缓删除；项目 FK 已由业务编号 UNIQUE 支持，状态先残余过滤 |
| 36 | `defects.ix_defects_assignee_status_id` | 保留；FK `assignee_id` 的必要左前缀，兼顾现有查询 |
| 37 | `defects.ix_defects_reporter_id` | 保留；FK `reporter_id` 的必要左前缀，兼顾现有查询 |
| 38 | `test_attempt_defects.ix_attempt_defects_defect_attempt` | 保留；FK `defect_id` 的必要左前缀，兼顾现有查询 |
| 39 | `test_attempt_defects.ix_test_attempt_defects_linked_by` | 保留；FK `linked_by` 的必要左前缀，兼顾现有查询 |

## 4. 41 个 FK 的引用动作审计

结论：全部保留 **ON DELETE RESTRICT / ON UPDATE RESTRICT**。父端全部引用主键；对应类型均为有符号 BIGINT。
核心父对象使用归档、禁用或关闭，历史身份/执行证据不能因父删除被级联清除。主键不做业务变更，故不需要 ON UPDATE CASCADE。
可空表示可没有关系，不代表删除父行时应自动清空历史引用；nullable Plan、reviewed_by、assignee、执行者及导入来源同样保留 RESTRICT。
这些动作仅在存在子引用时限制父键删除/更新；在 InnoDB 中立即检查。[MySQL Referential Actions](https://dev.mysql.com/doc/refman/8.0/en/create-table-foreign-keys.html)

| # | 子表 FK | 父端 | 可空 | ON DELETE | ON UPDATE | 支持索引 | 保留理由 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | `projects.created_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_projects_created_by` | 保留用户及操作/指派引用 |
| 2 | `project_members.project_id` | `projects.id` | 否 | RESTRICT | RESTRICT | `PK(project_id,user_id)` | 防止删除被引用的项目 |
| 3 | `project_members.user_id` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_members_user_status_project` | 保留用户及操作/指派引用 |
| 4 | `project_counters.project_id` | `projects.id` | 否 | RESTRICT | RESTRICT | `PK(project_id,entity_type)` | 防止删除被引用的项目 |
| 5 | `requirements.project_id` | `projects.id` | 否 | RESTRICT | RESTRICT | `uq_requirements_project_key` | 防止删除被引用的项目 |
| 6 | `requirements.created_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_requirements_created_by` | 保留用户及操作/指派引用 |
| 7 | `test_cases.project_id` | `projects.id` | 否 | RESTRICT | RESTRICT | `uq_test_cases_project_key` | 防止删除被引用的项目 |
| 8 | `test_cases.created_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_test_cases_created_by` | 保留用户及操作/指派引用 |
| 9 | `test_automation_identities.project_id` | `projects.id` | 否 | RESTRICT | RESTRICT | `uq_automation_identity` | 防止删除被引用的项目 |
| 10 | `test_automation_mappings.automation_identity_id` | `test_automation_identities.id` | 否 | RESTRICT | RESTRICT | `uq_automation_mapping_identity` | 保留身份与用例绑定 |
| 11 | `test_automation_mappings.test_case_id` | `test_cases.id` | 否 | RESTRICT | RESTRICT | `ix_automation_mappings_case_status` | 保留身份与用例绑定 |
| 12 | `test_automation_mappings.created_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_automation_mappings_created_by` | 保留用户及操作/指派引用 |
| 13 | `test_steps.test_case_id` | `test_cases.id` | 否 | RESTRICT | RESTRICT | `PK(test_case_id,step_order)` | 父对象归档；关联由显式操作维护 |
| 14 | `test_case_requirements.requirement_id` | `requirements.id` | 否 | RESTRICT | RESTRICT | `PK(requirement_id,test_case_id)` | 父对象归档；关联由显式操作维护 |
| 15 | `test_case_requirements.test_case_id` | `test_cases.id` | 否 | RESTRICT | RESTRICT | `ix_trace_case_requirement` | 父对象归档；关联由显式操作维护 |
| 16 | `test_case_requirements.linked_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_test_case_requirements_linked_by` | 保留用户及操作/指派引用 |
| 17 | `test_case_requirements.reviewed_by` | `users.id` | 是 | RESTRICT | RESTRICT | `ix_test_case_requirements_reviewed_by` | 保留用户及操作/指派引用 |
| 18 | `test_plans.project_id` | `projects.id` | 否 | RESTRICT | RESTRICT | `uq_test_plans_project_key` | 防止删除被引用的项目 |
| 19 | `test_plans.created_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_test_plans_created_by` | 保留用户及操作/指派引用 |
| 20 | `test_plan_cases.test_plan_id` | `test_plans.id` | 否 | RESTRICT | RESTRICT | `PK(test_plan_id,test_case_id)` | 父对象归档；关联由显式操作维护 |
| 21 | `test_plan_cases.test_case_id` | `test_cases.id` | 否 | RESTRICT | RESTRICT | `ix_plan_cases_case_plan` | 父对象归档；关联由显式操作维护 |
| 22 | `test_plan_cases.added_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_test_plan_cases_added_by` | 保留用户及操作/指派引用 |
| 23 | `test_runs.test_plan_id` | `test_plans.id` | 是 | RESTRICT | RESTRICT | `ix_runs_plan_created_id` | 父对象归档；关联由显式操作维护 |
| 24 | `test_runs.created_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_test_runs_created_by` | 保留用户及操作/指派引用 |
| 25 | `test_run_projects.test_run_id` | `test_runs.id` | 否 | RESTRICT | RESTRICT | `PK(test_run_id)` | 保留执行归属 |
| 26 | `test_run_projects.project_id` | `projects.id` | 否 | RESTRICT | RESTRICT | `ix_run_projects_project_run` | 防止删除被引用的项目 |
| 27 | `test_run_cases.test_run_id` | `test_runs.id` | 否 | RESTRICT | RESTRICT | `uq_run_cases_run_case` | 保留执行、导入或缺陷证据 |
| 28 | `test_run_cases.test_case_id` | `test_cases.id` | 否 | RESTRICT | RESTRICT | `ix_run_cases_case_run` | 保留执行、导入或缺陷证据 |
| 29 | `test_run_case_steps.test_run_case_id` | `test_run_cases.id` | 否 | RESTRICT | RESTRICT | `PK(test_run_case_id,step_order)` | 保留执行、导入或缺陷证据 |
| 30 | `test_imports.test_run_id` | `test_runs.id` | 否 | RESTRICT | RESTRICT | `ix_imports_run_time_id` | 保留执行、导入或缺陷证据 |
| 31 | `test_imports.imported_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_test_imports_imported_by` | 保留用户及操作/指派引用 |
| 32 | `test_attempts.test_run_case_id` | `test_run_cases.id` | 否 | RESTRICT | RESTRICT | `uq_attempts_run_case_no` | 保留执行、导入或缺陷证据 |
| 33 | `test_attempts.executed_by` | `users.id` | 是 | RESTRICT | RESTRICT | `ix_test_attempts_executed_by` | 保留用户及操作/指派引用 |
| 34 | `test_attempts.import_id` | `test_imports.id` | 是 | RESTRICT | RESTRICT | `uq_attempts_import_run_case` | 保留执行、导入或缺陷证据 |
| 35 | `test_attempts.automation_mapping_id` | `test_automation_mappings.id` | 是 | RESTRICT | RESTRICT | `ix_attempts_automation_mapping` | 保留执行、导入或缺陷证据 |
| 36 | `defects.project_id` | `projects.id` | 否 | RESTRICT | RESTRICT | `uq_defects_project_key` | 防止删除被引用的项目 |
| 37 | `defects.reporter_id` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_defects_reporter_id` | 保留用户及操作/指派引用 |
| 38 | `defects.assignee_id` | `users.id` | 是 | RESTRICT | RESTRICT | `ix_defects_assignee_status_id` | 保留用户及操作/指派引用 |
| 39 | `test_attempt_defects.attempt_id` | `test_attempts.id` | 否 | RESTRICT | RESTRICT | `PK(attempt_id,defect_id)` | 保留执行、导入或缺陷证据 |
| 40 | `test_attempt_defects.defect_id` | `defects.id` | 否 | RESTRICT | RESTRICT | `ix_attempt_defects_defect_attempt` | 保留执行、导入或缺陷证据 |
| 41 | `test_attempt_defects.linked_by` | `users.id` | 否 | RESTRICT | RESTRICT | `ix_test_attempt_defects_linked_by` | 保留用户及操作/指派引用 |

当前步骤的替换、计划清单移除和错误缺陷链接纠正，是对相应子行的显式操作，与父端 RESTRICT 不矛盾。
RESTRICT 不会阻止修改子表 FK 指向另一个存在的父行，也不会阻止直接删除没有下游引用的 Attempt/快照。
因此“主键不可变、核心行不物理删、历史内容不可覆盖”仍须由 Service 写入口执行，不能宣称 41 FK 已完成不可变审计。
负责人有效性在新指派和重开时检查；已 CLOSED 缺陷允许保留已停用负责人，与全程保留用户 FK 一致。

## 5. 跨 Project 完整性边界

### 5.1 当前数据库与 Service 分工

| 数据路径 / 规则 | 当前 PK/FK/UNIQUE 能保证 | 必须由 Service 事务保证 |
| --- | --- | --- |
| Project ↔ Member | 项目/用户存在，项目内每用户只有一条成员行 | ACTIVE 用户、成员状态与角色；ADMIN 的全局例外 |
| Requirement ↔ Case | 两端存在，关系对不重复 | 两端 project_id 相等 |
| Plan ↔ Case | 两端存在，计划内不重复 | Plan.project_id = Case.project_id |
| Run ↔ RunProject ↔ 可选 Plan | 每 Run 至多一条归属，两端存在，Plan 可空 | 每 Run 至少一条归属；非空 Plan 与归属同项目；原子生成非空范围 |
| RunCase ↔ Run / Case | 两端存在，Run×Case 唯一 | Case.project_id 与 Run 独立归属相同；范围与快照冻结 |
| Identity ↔ Mapping ↔ Case | 外部身份项目内唯一，身份至多一个绑定，Case 允许多个绑定 | Identity.project_id = Case.project_id；已使用绑定不可改指向 |
| Attempt ↔ RunCase / Import | 来源对象存在；尝试序号、请求令牌、批次内执行项唯一 | Import.test_run_id = RunCase.test_run_id，比仅同项目更严格 |
| Attempt ↔ Mapping / Import / RunCase | 独立 FK 各自有效 | Mapping.test_case_id = RunCase.test_case_id；Identity 与 Run 同项目；namespace/source 匹配；每 RunCase 单自动实现 |
| Attempt ↔ Defect | 两端存在，证据链接不重复 | Attempt 沿 RunCase/RunProject 得出的项目 = Defect.project_id，且 Attempt.status=FAIL |
| 业务对象 ↔ 创建者/执行者/报告人/负责人 | 用户存在，历史引用不被父删除清空 | 操作当时授权与状态；新负责人是有效本项目 DEVELOPER 或有效 ADMIN |

Project 的状态与归属创建后不可迁移也需 Service 协调；单个合法 project_id 不等于关联链必然属于同项目。
这些检查应在既定父行锁及事务中执行，读取过的关联集合锁后需复核；不能用事务外查一次作为并发保证。

### 5.2 可加强的数据库约束及本轮取舍

已有的复合 PK、13 个 UNIQUE、41 个 FK、NOT NULL 与行内 CHECK 应在 DDL 阶段真正落地；它们能阻止重复关系、缺失父对象、重复身份/编号及非法状态。
CHECK 无法读取另一张表来证明两端同项目；本方案不把跨表规则伪装为已受数据库 CHECK 保护。[MySQL CHECK 限制](https://dev.mysql.com/doc/refman/8.0/en/create-table-check-constraints.html)

| 备选加强方式 | 能加强什么 | 当前取舍 |
| --- | --- | --- |
| 关联表带 project_id，分别复合 FK 指向父表 (project_id,id) | 在 DB 拒绝跨项目的 Requirement–Case、Plan–Case 等关联 | 会增加冗余列、父候选键/索引并改变当前规范化取舍；本轮不加字段/FK，不采用 |
| Attempt 冗余 run_id/case_id，再复合 FK 约束 Import、RunCase、Mapping | 在 DB 证明同 Run、同 Case | 同样增加重复事实与约束；本轮不改 155 字段模型 |
| defects(project_id,assignee_id) → project_members(project_id,user_id) | 利用现有列强制负责人有本项目成员行 | 不能表达不必是项目成员的 ADMIN 例外，也不能检查 ACTIVE/DEVELOPER；直接采用会改变已确认权限，不采用 |
| 跨表校验触发器及不可变触发器 | 理论上可在直接 SQL 写入时检查项目或阻止历史变更 | 需要额外锁定/并发与维护设计，当前选择 Service 集中业务规则，本轮不引入 |
| 反向 FK 强制每 Run 必有 RunProject | 试图让父表同时依赖归属行 | 与现有正向 FK 形成插入循环；InnoDB 无延迟到提交时的 FK 检查，不能靠关闭检查完成正常创建，不采用 |

结论：本轮未新增约束数量；继续以数据库结构约束提供基础完整性，以 Service 承担上述跨表业务不变量。
“目前留在 Service”不等于数据库理论上永远无法加强；采用冗余复合键或触发器属于以后另行评审的实现取舍，不是待补的 V1 产品功能。

## 6. 三份设计文件的一致性核对

- 两份 Markdown 与独立 ER 使用同一 20 表命名；执行项、步骤快照、Attempt 概念分离。领域表清单的 PK、FK 目标及 UNIQUE 与数据库第 2 节一致。
- ER 原省略 14 条用户引用，本轮补齐到 41 条，并以子表 FK 字段名区分相同两表间多种关系；领域模型中的 Mermaid 块与独立文件完全相同。
- 修正图例：基数由端点标记表示，实线/虚线区分 FK 是否参与子表 PK。必有归属、非空 Run 清单/步骤和成功批次等最少子行数明确属于 Service 保证。
- Plan 可空；身份与绑定一对多的方向、映射唯一性、Attempt 来源可空性均与字段一致。ER 表示关系，155 个字段的完整类型/默认值以数据库第 2 节为准，未假称图内列出了全部字段。
- 统一 37 个非 PK 索引的数字和查询依据，移除领域文档中仍依赖已暂缓状态索引的描述。
- 统一归档项目只读与 ACTIVE 成员读取权限；新分配负责人需有效，但 CLOSED 历史负责人可以停用。用户管理仍集中于 ADMIN，清除“注册”表述可能暗示自助提权入口的歧义。
- 当前状态均取最新 attempt_no；NOT_RUN 无行、SKIPPED 与 BLOCKED 分列、实际执行数 P+F、全 SKIPPED 可完成但通过率 N/A，三者无冲突。
- 文档结构核验包含字段/FK 计数、父主键及类型、唯一约束与索引一致、全部 FK 左前缀支持、ER 对应及链接有效性；这不等于 MySQL 实测或业务测试。

## 7. 风险与冻结建议

**建议冻结 V1 Domain Model 的领域边界和逻辑关系模型**，保留 20 表 / 155 字段 / 41 FK / 37 非 PK 索引作为下一阶段输入。
这是一项审计建议；文档仍标为待用户确认的推荐方案，本轮没有擅自标记“已批准冻结”。

仍存在的风险：

1. Service 检查与锁定仍是跨项目隔离、Run 必有归属、映射/执行证据不可变的关键；任意直连 SQL 可绕过。实现阶段需覆盖失败回滚与并发边界。
2. 拆分归属与身份增加 JOIN；当前严格职责划分不会自动增强跨表约束。必须按本审计列出的路径完整校验。
3. 删除两条状态索引后，大项目的筛选/统计可能更慢；没有运行性能基准。身份复合唯一键的长度、16KB 页及 DYNAMIC 行格式也尚未在目标实例验证。
4. V1 不保留完整需求全文、映射重绑或关系变更历史；Run 快照保存采纳定义，不能证明自动化实现逐步执行该定义。
5. 单个 RunCase 只接受一个自动实现的尝试流；旧报告追加按接受次序影响当前结果；全 SKIPPED 的完成语义必须在页面和报告中明确。以上是已写明的边界，不在本轮扩展。

下一阶段需验证正式 DDL、唯一冲突、引用动作、CHECK 与并发事务；本轮只审计设计，不提前执行。

## 8. 工作区状态与修改范围

本轮仅修改两份设计文档、ER 和 README 审计入口，并新增本审计文件；保留之前的 pom.xml、src 占位骨架、环境审计与 IDEA 配置。
HEAD 保持 9fd1cf43e808786c7bd8b86c7c7cf9fa81b20a3b，未 commit、未 push。未跟踪的 docs/src 不会全部出现在普通 git diff --stat 中。

```text
## main...origin/main
 M README.md
 M pom.xml
?? docs/
?? src/
```
