# QATrack V1 数据库落地验证报告

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

日期：2026-09-06。版本：[QATrack V1 Domain Model Freeze v1.0](DOMAIN-FREEZE-v1.0.md)。
本轮先选择 Run 归属方案 A、宣布冻结，再编写和执行 SQL。未实现 Java/Servlet/DAO/Service/前端，未 commit/push。

## 1. 真实对象与环境

| 对象 | 实测数量 |
| --- | --- |
| 业务表 / 字段 | **19 / 154** |
| PK | **19** |
| FK | **40** |
| UNIQUE（不含 PK） | **13** |
| CHECK | **51，全部 ENFORCED=YES** |
| 普通非唯一索引 | **24** |
| 非 PK 索引（含 UNIQUE） | **37** |
| 所有索引（含 PK） | **56** |
| 持久化视图 / 触发器 / 存储例程 | **0 / 0 / 0** |

最终验证库为 `qatrack_v1_verify_freeze10`，实际创建上述 19 表与约束/索引，没有额外自动生成的 FK 索引。
测试期间临时创建 qt_assert_sql、qt_assert_value、qt_finish 三个辅助过程及会话临时结果表，成功后全部删除；它们不属于产品结构。
早期探针/验证库在收尾时清除，最终验证库保留供复查。

使用 MySQL Community Server **8.0.46 Win64** 现有二进制启动独立实例，监听 127.0.0.1:13306，未开启 MySQL X。
数据目录为 <PROJECT_ROOT>/../QATrack-local-validation/freeze-v1-20260906/data；InnoDB 页为 16384 字节，全表 DYNAMIC。
foreign_key_checks=1，严格 SQL 模式、执行会话 UTC；未禁用 FK/CHECK，未用 --force、IGNORE 或 upsert 掩盖错误。
未改 MySQL80 Windows 服务、账户、配置及已有数据库。验证实例收尾正常关闭，数据仍保留在 E 盘。

[真实对象/引用动作/索引输出](../database/verification/inspect.sql.tsv) 与 [154 字段元数据](../database/verification/columns.json) 已核对。
字段类型、可空性、DEFAULT、字符排序规则逐项与冻结模型一致。此次隔离验证不等于已部署到原 MySQL80 业务库。

## 2. 从空库执行

[verify.ps1](../database/verify.ps1) 在新数据库依次执行，全部退出 0：

1. [schema.sql](../database/schema.sql)：从空库完整创建；不含 DROP/IF NOT EXISTS，不是迁移脚本。
2. [seed.sql](../database/seed.sql)：680 行在一个事务中完整导入，每表至少 10 行。
3. [constraint-tests.sql](../database/constraint-tests.sql)：**178 / 178 PASS，0 FAIL**。
4. [queries.sql](../database/queries.sql)：10 组核心查询及 2 条 EXPLAIN ANALYZE 成功。
5. [service-invariants.sql](../database/service-invariants.sql)：**22 项检查均为 0 违规**。
6. [inspect.sql](../database/inspect.sql)：真实对象数、索引、全部 RESTRICT 动作及 CHECK 生效状态符合设计。

| 表 | 实际行数 |
| --- | --- |
| `users` | 10 |
| `projects` | 10 |
| `project_members` | 20 |
| `project_counters` | 40 |
| `requirements` | 30 |
| `test_cases` | 40 |
| `test_automation_identities` | 30 |
| `test_automation_mappings` | 30 |
| `test_steps` | 80 |
| `test_case_requirements` | 40 |
| `test_plans` | 20 |
| `test_plan_cases` | 50 |
| `test_runs` | 30 |
| `test_run_cases` | 50 |
| `test_run_case_steps` | 100 |
| `test_imports` | 20 |
| `test_attempts` | 50 |
| `defects` | 20 |
| `test_attempt_defects` | 10 |
| **合计** | **680** |

10 个项目各有成员、需求、READY/DRAFT 用例、两个计划、三个 Run 和缺陷。
场景包括有计划回归、无计划手工 Ad-hoc、无计划 JUnit 全 SKIPPED；FAIL→PASS 的失败链接保留，另有 BLOCKED 和零 Attempt 的 NOT_RUN。
Case 在多个计划/执行中复用，同一 Case 可有 web/firefox 实现；每 RunCase 自动化流仍用单一映射。
密码字段为随机且已丢弃输入计算的 PBKDF2 哈希，不提供可登录测试口令。

## 3. 约束验证

[完整 178 项输出](../database/verification/constraint-tests.sql.tsv)：

| 测试组 | 数量 | 结果 |
| --- | --- | --- |
| FK 无父对象 | 40 | 全部命中指定 FK，errno 1452 |
| 命名 CHECK | 51 | 全部命中指定 CHECK，errno 3819 |
| UNIQUE | 13 | 全部命中指定唯一键，errno 1062 |
| DELETE RESTRICT | 12 个实际被引用父表 | 全部拒绝，errno 1451 |
| UPDATE RESTRICT | 同上 12 个父表 | 全部拒绝，errno 1451 |
| 其他边界及 Service 缺口探针 | 26 | 符合预期拒绝或预期接受 |
| 行数与历史事实断言 | 24 | 全部通过 |

覆盖项目内业务编号冲突、跨项目编号复用、Run×Case、RunCase×attempt_no、批次内执行项、请求/提交幂等键、自动化身份唯一。
身份测试验证跨项目复用、大小写/尾部空格区别及超长拒绝；Run 项目 NULL/不存在拒绝，可空 Plan 接受。
NOT_RUN 禁止入 Attempt、SKIPPED 合法，结束时间、人工/自动化来源组合、复核字段组合、失败摘要及缺陷处理说明均受 CHECK 约束。

全部 40 FK 的 DELETE/UPDATE 动作另从 INFORMATION_SCHEMA 核对为 RESTRICT。
按父表测试删除不意味着分别证明同父的每条 FK 单独触发；40 个无父引用测试另逐条核对 FK 名称。
每个修改型测试均回滚，最后全部表行数不变，22 项数据一致性检查证明种子未被故意错误探针污染。

首轮两项因相邻 CHECK 先拒绝而未命中指定名称：Run 时间 CHECK 改为与状态集合校验等价但职责分离的表达式；
Attempt 非法状态测试改用无失败摘要的记录。合法数据集合、字段和冻结结构不变，最终从新空库重跑通过。
独立探针证实 MySQL 8.0.46 允许 RESTRICT FK 列参与 CHECK，并返回 3819 拒绝非法组合，修正旧稿一律留给 Service 的过度保守描述。

## 4. 仍由 Service 保证的规则

实际测试接受后回滚的违规包括：

- 跨项目 Requirement–Case、Plan–Case、Run–Plan、RunCase–Case、Identity–Case、Attempt–Defect。
- Attempt 的 Import 不同 Run、Mapping 不同 Case、namespace 不匹配。
- 覆盖历史快照/Attempt、已使用绑定重指向、终态 Run 新增尝试、PASS 链接缺陷、负责人角色错误。

此类测试 PASS 意为“确认数据库没有保证该业务规则”，**不表示业务数据合法**。
此外，单 RunCase 单自动实现、用户/成员权限、最少子项、步骤连续、READY/完成/归档条件、归属不可迁移、
并发编号、事务原子性、幂等载荷、乐观锁、覆盖复核内容及 AI 预览版本仍由 Service 保证。

每 Run 必有项目、来源字段组合、复核字段组合已由 DB 保证，不再归为只能由 Service 保证。
未增加跨表触发器或冗余复合 FK。[22 项一致性输出](../database/verification/service-invariants.sql.tsv) 只证明当前种子合规，不保护未来直连写入。

## 5. 核心查询实测

[查询和执行计划](../database/verification/queries.sql.tsv)：

- 项目 1：ACTIVE 需求 3、有效覆盖 1；无任何用例为 103，无有效覆盖为 102/103。
- Run 101：N=3、P=2、F/B/S=0、NOT_RUN=1；实际执行 2，通过率 100%，执行率/记录完成率 66.67%。
- Case 101 的 FAIL→PASS 历史及原 FAIL 缺陷证据保留；Run 103 为已完成的全 SKIPPED。
- 项目 Run 查询使用 ix_runs_project_created_id；最新 Attempt 使用 uq_attempts_run_case_no 反向扫描。

执行计划仅基于 680 行样本，不宣称满足生产规模或课程 100 并发要求；尚无 Java Service 并发集成测试。

## 6. 文件及 Git 边界

新增 database 下的 schema、seed、约束验证、核心查询、一致性检查、对象检查、PowerShell 复现脚本及实测证据。
新增冻结决策和本报告；更新领域/数据库文档、ER、README；旧 V1-DESIGN-AUDIT.md 标为冻结前历史。

普通 git diff 只显示已跟踪的 README.md/pom.xml；database/docs/src 未跟踪，需同时看 git status 和文件清单。
pom.xml、.gitignore、ENVIRONMENT-AUDIT.md 和 12 个 src/.gitkeep 保持原样，本轮不改 IDEA 配置。
HEAD 保持 9fd1cf43e808786c7bd8b86c7c7cf9fa81b20a3b；未 commit/push。

实际 git diff --stat（相对 HEAD，含上轮保留的 pom.xml 修改）：

```text
 README.md | 77 +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++----
 pom.xml   | 23 ++++++++++++++++++-
 2 files changed, 95 insertions(+), 5 deletions(-)
```

git status --short --branch：

```text
## main...origin/main
 M README.md
 M pom.xml
?? database/
?? docs/
?? src/
```

新增 database/ 与 docs/ 文件不会出现在以上普通 diff 统计中。执行文件 SHA-256 与实测摘要见 [manifest.json](../database/verification/manifest.json)。
