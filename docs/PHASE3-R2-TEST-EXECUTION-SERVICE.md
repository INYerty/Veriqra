# Phase 3 Round 2 — Test Plan, Run and Execution Service

日期：2026-09-16。基线提交 `a501822 feat(service): add project and test asset business logic`；开始时 `main` 与 `origin/main` 同步且工作区干净。本轮只实现 TestPlan、TestRun 和人工 TestExecution Service，没有修改冻结 schema、seed、ConnectionPool、JdbcTransactionManager 或 Maven 依赖。

## 1. TestPlan workflow

`TestPlanService` / `DefaultTestPlanService` 提供 create、get、listCases、update、archive、addCase 和 removeCase。创建事务按 Project → actor User/Membership → PLAN Counter → TestCase → 新 Plan → PlanCase 执行：原子分配 PLAN 编号，插入 DRAFT Plan，并可插入显式给出的初始范围。任一步失败时 Counter、Plan 和全部关系一起回滚。

TestPlan 状态严格使用冻结枚举 DRAFT、READY、ARCHIVED：

- DRAFT 可为空，也可包含同项目的 DRAFT/READY Case；ARCHIVED Case 不能新加入。
- DRAFT → READY 要求范围非空，并在锁内确认每个成员 Case 都是 READY。
- READY → DRAFT 允许。
- DRAFT/READY → ARCHIVED 通过独立 archive 操作；ARCHIVED 终态只读。
- addCase/removeCase 是计划范围修改：锁 Case、再锁 Plan，比较调用方看到的 Plan lockVersion，递增版本并使 Plan 回到 DRAFT，然后修改关系。重复 add 抛 Conflict，不产生第二行；不存在关系的 remove 抛 NotFound。

冻结表 `test_plan_cases` 没有 sort_order/case_order；展示顺序继续使用 DAO 已定义的 Case key_no + id。本轮没有虚构 reorder API 或顺序列。

## 2. TestRun creation

`TestRunService` / `DefaultTestRunService` 提供 Plan-based 和 Ad-hoc 两个显式入口。冻结 `TestRunStatus` 只有 IN_PROGRESS、COMPLETED、CANCELLED，没有 PLANNED；因此创建成功即表示 start，初始状态固定为 IN_PROGRESS，没有伪造单独 start 状态。

两种创建都先以 `ProjectDao.findByIdForShare` 锁同一 Project，读取数据库中的 actor/User/Membership 权限并在锁后确认 Project ACTIVE，再按 ID 升序锁定当前 TestCase。随后一次事务插入 TestRun、全部 TestRunCase 和全部 TestRunCaseStep。快照不是异步任务；任何一行失败都会回滚整个 Run。

## 3. Snapshot semantics

TestCase/TestStep 是当前可编辑定义；TestRunCase/TestRunCaseStep 是创建 Run 时的不可变历史副本。快照复制：title、description、preconditions、priority，以及按 step_order 排列的 action/expectedResult。创建时只接受 READY Case，并再次确认步骤非空且为 1..N。

Run 创建持有每个 Case 的 `FOR UPDATE` 锁直至提交，所以 TestCaseService 的当前定义编辑不能在标题读取和步骤复制之间穿插。后续编辑 Case/Step 或修改 Plan 范围只作用于当前定义，既有 Run 查询只读取快照表，不回连当前 Case 替换历史内容。

## 4. Plan vs Ad-hoc run

Plan-based Run 要求命令中的 projectId 与 Plan.projectId 相同，Plan 为 READY，范围非空，且当前全部成员 Case 仍为同项目 READY。Service 先取得范围 ID、按固定顺序锁 Case，再锁 Plan 并重新读取关系；集合发生变化则拒绝本次创建，不自动 retry。

Ad-hoc Run 要求显式、非空、无重复的 Case ID 集合，全部属于同一 ACTIVE Project 且为 READY。持久化的 test_plan_id 为 NULL，不创建假 Plan。schema 没有 Run source 列，因此本轮不虚构 AD_HOC/IMPORT enum；Import Run 入口留给后续 ImportService。

## 5. Run status transitions

状态机为：

```text
create/start -> IN_PROGRESS -> COMPLETED
                            -> CANCELLED
```

COMPLETED/CANCELLED 均为终态，不 reopen。complete 要求 RunCase 数量大于 0，且每个 RunCase 至少已有一个 Attempt；最后结果可以是 PASS、FAIL、BLOCKED 或 SKIPPED。cancel 不要求所有执行项已有结果。状态变化使用 Run lockVersion，并由注入的 Clock 写 endedAt。

## 6. TestExecution rules

`TestExecutionService` / `DefaultTestExecutionService` 提供 recordAttempt、listAttempts 和 currentOutcome。人工提交只接受 actorUserId、runCaseId、结果、可选时长/说明/失败信息及 submissionKey；调用方不能提供 attempt_no、executedBy、import/mapping ID、recordedAt 或数据库 ID。

写入前按 Project → User/Membership → Run → RunCase → Attempt 顺序锁定，并确认 Project ACTIVE、actor 为 ACTIVE ADMIN 或 ACTIVE TESTER、Run 为 IN_PROGRESS、RunCase 属于该 Run。DAO 仍只有 insert/query；旧 Attempt 永不 update/delete。非 FAIL 结果携带 failureMessage 会在 Service 层拒绝，负 duration 也会拒绝。

相同 submissionKey 的顺序重试比较业务载荷：完全一致返回原 Attempt，不生成新序号；不同载荷抛 Conflict。该判定先于 Run 终态对“新增 Attempt”的拒绝，因此请求成功后即使 Run 已完成，完全相同的重试仍返回原记录；新令牌仍会被终态拒绝。不存在的 submission key 不再使用锁定读，数据库 UNIQUE 是并发裁决点；1062 的原事务回滚后只用新事务恢复并核对载荷。

## 7. Attempt sequence

序号分配严格复用 Phase 2 协议：先锁 Run，再锁 RunCase，使用 `findLatestByRunCaseForUpdate` 做当前锁定读，空集合分配 1，否则检查 Integer 上界后加一。普通读不能替代该 current read，因为事务前置查询已经建立 REPEATABLE READ snapshot。空子集合的锁定读在不同 RunCase 首次并发时可能触发 InnoDB 1213；仅 `TestExecutionService` 在完整原事务已回滚后从头重跑一次，第二次失败直接上抛。没有 `SELECT MAX + 1`、DAO retry 或通用重试框架。

本轮保留 `listByTestPlanForUpdate`，用于 Plan 锁后绕过旧 REPEATABLE READ 快照，以当前锁定读复核范围；它只读取/锁定关系表并按 Case ID 排序，不在 Plan 之后反向锁 TestCase。Final Service Review 移除了 `findBySubmissionKeyForUpdate`，避免对不存在唯一键加 gap lock。DAO 不包含权限、状态机、事务所有权或 retry。

## 8. NOT_RUN semantics

`currentOutcome` 返回 `Optional<TestAttemptStatus>`。空 Optional 即 NOT_RUN；数据库不会插入 status=NOT_RUN。SKIPPED 是真实 Attempt 状态，与 NOT_RUN 分开。FAIL 后 PASS、BLOCKED 后重测等都继续保留为多条事实，当前结果只取最大 attempt_no。

## 9. Project archive concurrency coordination

Round 1 archive 使用 Project `FOR UPDATE` 后检查 IN_PROGRESS Run。本轮所有 Run 创建和执行写入先取得同一 Project `FOR SHARE` 并在锁后检查 ACTIVE：

- Run 创建先持有 Project SHARE，归档等待；Run 提交后归档看到 IN_PROGRESS Run 并拒绝。
- 归档先取得 Project UPDATE 并提交时，后来的创建在取得 SHARE 后看到 ARCHIVED 并拒绝。

真实双线程测试在 Run insert 前暂停创建事务，确认 archive 被数据库锁阻塞；释放创建后 Run 成功、archive 以 Conflict 结束，未出现 ARCHIVED Project + 新 IN_PROGRESS Run。

## 10. Permissions

继续复用 `ProjectAccessPolicy`，不接受调用方角色：

| Actor | Plan/Run/Attempt write | Read |
| --- | --- | --- |
| ACTIVE ADMIN | 全项目允许 | 全项目允许 |
| ACTIVE TESTER member | 所属项目允许 | 所属项目允许 |
| ACTIVE DEVELOPER member | 拒绝 | 所属项目允许 |
| INACTIVE member / DISABLED user | 拒绝 | 拒绝 |

归档 Project 只读；归档 Plan 只读；终态 Run 可读但不能追加 Attempt。

## 11. Transaction boundaries

所有 public Service 操作经 `ServiceTransaction → JdbcServiceTransaction → JdbcTransactionManager` 执行。`JdbcServiceDaoFactory` 在回调给出的同一 Connection 上创建本轮所需 DAO。Service/DAO 没有 commit、rollback、setAutoCommit、第二连接或 nested transaction；DAO 和事务管理器不自动 retry。Final Service Review 只为幂等的 `recordAttempt` 增加了局部、最多一次的 1213 全事务重试。

三个故障注入场景使用真实 MySQL 验证：

1. PLAN Counter 已递增、Plan 已写、第二个 PlanCase 写入后失败：Counter 回到 1，Plan/关系均不存在。
2. Run、RunCase 和部分 RunCaseStep 已写后失败：Run、所有快照头和步骤全部不存在。
3. Run/RunCase 已锁且 Attempt 已 insert 后抛错：Attempt 回滚；下一次正常提交仍取得 attempt_no=1。

## 12. Lock order

本轮实际顺序是冻结全局顺序的子集：Project → User/Membership → Counter → TestCase → Plan → Run → RunCase → Attempt/关系。多个 Case/RunCase 一律按 ID 升序锁定。

Plan-based Run 在加锁前可用普通读取得候选范围，但锁定 Case 和 Plan 后必须重新读取并比较范围。Run completion 与 recordAttempt 都锁同一 Run；completion 在 Run 锁内检查每个 RunCase 是否已有 Attempt，避免检查完成后继续追加。当前测试证明指定编排，不声明所有未来多对象事务绝对无死锁。

## 13. Tests

本轮新增 16 项 MySQL integration tests，未为了数量增加新的 fast unit test：

- TestPlanServiceIntegrationTest：5 项，覆盖创建/READY/范围回 DRAFT/版本、跨项目、重复、归档、权限、Counter+Plan+关系回滚，以及旧一致性读后发生范围提交时 READY 校验通过锁定读拒绝旧范围。
- TestRunServiceIntegrationTest：5 项，覆盖 Plan/Ad-hoc、快照独立、跨项目、归档 Project、快照失败回滚、Project archive 并发协调、CANCELLED，以及 Plan Run 在旧一致性读后通过锁定读拒绝已变化范围。
- TestExecutionServiceIntegrationTest：5 项，覆盖 NOT_RUN、四种 Attempt、FAIL→PASS、令牌幂等/冲突、完成条件、终态后同请求仍幂等但新 Attempt 被拒绝、12 路并发序号、插入失败回滚及权限。
- 既有 TestPlanCaseDaoIntegrationTest 增加 1 项，证明普通读保持旧 REPEATABLE READ 视图，而新的锁定范围读能看到其后已提交的关系。

原 162 项全部保留，总数 178。新增 `findBySubmissionKeyForUpdate` 也在既有 TestAttempt DAO integration test 中做了真实锁定读取验证。

## 14. Known limitations

- schema 没有 PlanCase 顺序列和 Run source 列，因此没有 reorder，也没有公开 Import Run 伪入口。
- 创建 Run 就是 start；如以后需要 PLANNED，必须先修改冻结模型，本轮不伪造。
- 列表仍为完整列表，没有分页。
- Attempt 写入因 completion 协调而锁整个 Run；课程规模下优先保证语义清楚，没有提前做细粒度并发优化。
- submissionKey 支持已提交请求的相同载荷重试；跨不同 Run 的并发同 token 由数据库 UNIQUE 裁决，原事务回滚后的新事务将相同载荷恢复为原 Attempt、不同载荷转换为 Conflict，不泄露裸 1213。
- Defect、Automation、Import、Servlet、认证和前端不在本轮。

## 15. Next round

Round 2 完成后应先做针对性 Review，重点检查 Plan 范围变更版本、Plan/Case/Run 锁顺序、Run completion 与 Attempt 竞争、submissionKey 语义和快照故障回滚。通过并提交后，下一轮再选择 Defect 或其他 Service；不要把 Import/Automation/Servlet 混入本轮提交。

## Review findings

- CRITICAL：none。
- HIGH：none。
- MEDIUM：两项，均已修复。其一是 Plan 范围复核使用普通一致性读时可能停留在旧 REPEATABLE READ 快照；新增关系表当前锁定读，并由 Plan READY 与 Plan-based Run 在 Plan 锁后调用，DAO 及两个 Service 路径均有真实 MySQL 证据。其二是已成功提交的 submission key 在 Run 随后终态化后会先被状态校验拒绝；现在先锁 RunCase/Attempt 并完成同载荷幂等判断，仅对新 Attempt 执行 IN_PROGRESS 校验。
- LOW：none。实现过程中按冻结 Domain Model 补回“计划范围修改回 DRAFT 且递增 Plan 版本”的要求，并以真实 MySQL 覆盖；未发现遗留阻塞问题。
- INFORMATIONAL：实际锁顺序为 Project → User/Membership → Counter → TestCase → Plan → Run → RunCase → Attempt/关系；同类 ID 升序。当前 Project archive、Plan edit/Run create、TestCase edit/snapshot、complete/recordAttempt 路径未发现反序；并发测试只证明这些已编排路径，不声明未来流程绝对无死锁。

## Final verification

- `mvn test`：51 tests passed，BUILD SUCCESS。
- `mvn -Pmysql-tests test`：178 tests passed，BUILD SUCCESS。
- `mvn -Pmysql-tests clean package`：178 tests passed，WAR BUILD SUCCESS。
- `git diff --check`：通过。
- 集成测试仅使用 `127.0.0.1:13307/qatrack_test_r1`；清理后 test schema 为 0 tables，临时 MySQL 8.0.46 已关闭，13307 无监听。
- Windows `MySQL80` 开发服务仍为 Running；本轮未连接或修改 `localhost:3306/qatrack`。

## Targeted review conclusion

- A. 批准 Phase 3 Round 2。TestPlan、TestRun、snapshot、manual TestExecution 的状态、事务和当前锁定读均有真实 MySQL 证据。
- B. commit 前没有仍待修复的问题；本次 Review 发现的两项 MEDIUM 已最小修复并补测试。
- C. 验证与工作区检查通过后可以 commit/push；本轮自身不执行 commit/push。
- D. commit/push 后可以进入 Phase 3 Round 3，本轮不实现 Round 3 内容。
- E. 同 Connection 的 Service transaction、固定锁顺序、Counter 分配、父对象锁和 append-only 写入模式可以作为 Defect Service 的基础模板；Defect 自身的 FAIL 证据、同项目和 Attempt↔Defect 关系规则仍需按其领域单独实现，不能机械复制状态规则。
