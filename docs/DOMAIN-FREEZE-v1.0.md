# QATrack V1 Domain Model Freeze v1.0

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

冻结日期：2026-09-06。依据本轮用户授权，在重新检查当前设计后冻结领域边界与逻辑结构，随后进入 MySQL 8.0.46 落地验证。

## Run 项目归属最终选择

| 角度 | A：test_runs.project_id 非空，test_plan_id 可空 | B：1:1 test_run_projects |
| --- | --- | --- |
| 领域语义 | Project 是 Run 的必需归属；Plan 是可选来源，语义直接 | 为无独立生命周期的属性额外建立实体 |
| 3NF | 有 Plan 的子集存在 Plan→Project 的条件依赖；接受这一有意冗余，不声称所有含 NULL 的关系均有经典 3NF 证明 | 单表依赖更简单，但拆分后仍必须检查两条项目路径一致，不能把拆分等同完整业务规范化 |
| 查询复杂度 | 项目列表、权限与报表直接按 Run.project_id 查询 | 每次取得项目都需 JOIN 归属表 |
| FK/约束能力 | NOT NULL＋Project FK 直接保证每 Run 引用存在项目；可选 Plan FK 保证计划存在 | PK/FK 只能保证至多一条归属和引用存在，无法保证每 Run 至少一条 |
| Service 复杂度 | 创建一个 Run 行，校验可选 Plan 同项目 | 额外插入归属行、检查缺失、保持两行原子性；同项目检查仍不能省略 |
| 必有归属完整性 | 单行约束即可阻止项目为 NULL 或不存在 | 直接插入孤立 Run 不会被现有数据库约束拒绝 |
| 课程答辩 | 必需归属与可选来源易解释；明确条件冗余和 Service 边界 | 需额外解释无业务生命周期的一对一表及最少子行缺口 |

**采用 A，移除 test_run_projects。** 此决策按用户最新要求优先保证简单和必有归属完整性，修正此前为形式规范化偏向 B 的推荐。
主体与 N:M 关系继续按明确候选键及规范化原则组织；不会为了宣称“严格 3NF”隐瞒可选计划带来的条件重复事实。
项目与计划来源创建后不可迁移；非空 Plan 必须与 Run 同项目，这仍是 Service invariant。
本轮不额外添加冗余 project_id、父表冗余 UNIQUE、复合 FK 或跨表触发器来强制所有关系链同项目。

## 冻结范围

- 19 表、154 字段、19 PK、40 FK；保留 13 个 UNIQUE 和 24 个普通索引（共 37 个非 PK 索引）。落地后实测 51 个 CHECK 全部强制执行，详见 [数据库验证报告](DATABASE-VALIDATION-v1.0.md)。
- 移除归属表的两个字段，将一个非空 project_id 合入 test_runs；原项目归属索引替换为 Run 的项目/创建时间/id 索引，支持既有项目执行列表。
- Case 可编辑；RunCase 内容/步骤快照固定；Attempt 保留每次尝试，当前结果由最新 attempt_no 推导。
- Plan 可空；ADMIN 集中管理；SKIPPED 与 BLOCKED 分列；无 Attempt 才是 NOT_RUN。
- 自动化 Identity/Mapping 分离；逻辑用例允许多个绑定，同一 RunCase 的自动化尝试仍限定同一个实现。
- 不增加产品功能、版本表或 Java/前端实现；不 commit/push。

## 落地与变更控制

声明冻结在正式 DDL 编写之前作出。字段类型、默认值、CHECK 表达式与索引的最终可执行定义见后续生成的 database/schema.sql；真实验证结果另记数据库验证报告。
语法或 MySQL 限制导致的等价物理修正应同步文档并记录；不能借落地扩展领域边界。若发现改变关系/语义的结构问题，必须明确记录冻结修订，而非悄悄改变模型。
上一轮 V1-DESIGN-AUDIT.md 是 20 表历史审计，不再是当前结构依据。
