# Phase 3 Round 3 — Defect Service and Retest Workflow

日期：2026-09-20。基线提交为 `14b9242 feat(service): add test planning and execution workflows`；开始时 `main` 与 `origin/main` 同步且工作区干净。本轮只实现 Defect Service、失败证据和重测闭环，没有修改冻结 schema、seed、ConnectionPool、JdbcTransactionManager 或 Maven 依赖。

## 1. Defect creation

`DefectService` / `DefaultDefectService` 提供 evidence-backed create、get、listByProject、普通字段 update、显式状态 transition/reopen、证据 add/remove 和双向关系查询。创建入口要求一个现有 FAIL Attempt；Service 从 Attempt → RunCase → Run 取得真实项目，不相信调用方拼装的项目关系。

创建事务为：锁 Project 并确认 ACTIVE，锁 actor/可选 assignee，校验权限，分配 BUG Counter，按 Run → RunCase → Attempt 锁定失败证据，插入 OPEN Defect，再插入 TestAttemptDefect。任一步失败全部回滚。FAIL 不会自动生成缺陷，必须由用户显式调用创建入口。

## 2. BUG numbering

项目内 BUG 编号复用 `ProjectCounterDao.allocateNext(projectId, BUG)`。没有 `SELECT MAX + 1`、lazy counter 或 DAO retry。Counter、Defect 和首条 evidence 使用同一 JDBC Connection 和事务；故障注入测试在 evidence 已插入后抛错，证明 Counter 回到 1，Defect/link 均不存在。两个并发创建得到唯一的 BUG-1、BUG-2；测试只证明当前编排，不宣称无间隙或所有未来事务无死锁。

## 3. Evidence semantics

TestAttemptDefect 表示“这个 Defect 的具体失败执行证据”，不是 TestCase 标签。新增证据必须满足：Attempt 为 FAIL；Attempt → RunCase → Run 完整；Run.projectId 与 Defect.projectId 相同；Project ACTIVE；actor 有管理证据权限。已完成 Run 的 FAIL 仍可作为证据。

重复 add 返回现有关系，不生成第二行。remove 只纠正错误关系，不删除 Attempt 或 Defect；CLOSED Defect 的证据只读，必须先以新 FAIL 重开。V1 不保存 evidence link 的完整变更审计。

## 4. Status state machine

冻结状态机落实为：

```text
OPEN ------> IN_PROGRESS ------> RESOLVED ------> CLOSED
                 ^                    |              |
                 |                    v              v
                 +--------------- REOPENED <---------+
```

- OPEN/REOPENED 只能进入 IN_PROGRESS。
- IN_PROGRESS 只能进入 RESOLVED，且必须提供非空 resolutionNote。
- RESOLVED 只有满足重测验证后才能 CLOSED。
- RESOLVED/CLOSED 只能通过 `reopen` 携带一条新的 FAIL evidence 进入 REOPENED。
- REOPENED 清空当前 resolutionNote，保留原 Defect ID、project/key 和全部历史 evidence。
- OPEN、任意 enum 跳转、同状态覆盖和绕过 `reopen` 的 REOPENED 均被拒绝。

普通字段更新和状态转换分离；`UpdateDefectCommand` 不能设置 status，`TransitionDefectCommand` 不能修改 title/severity 等字段。

## 5. Resolve, close and reopen workflow

Tester/Admin 可以管理字段、证据、指派、状态转换、重开和关闭。Developer 可以报告缺陷；只有当自己是当前 assignee 时，才可执行 OPEN/REOPENED → IN_PROGRESS 和 IN_PROGRESS → RESOLVED。Developer 不能普通编辑、管理 evidence、重开或关闭。

新指派和重开必须将 assignee 设为 ACTIVE 项目 DEVELOPER，或 ACTIVE 平台 ADMIN；重开必须重新校验。CLOSED 对普通编辑和 evidence 变更只读，但可由 Tester/Admin 以新的同项目 FAIL 重开。

## 6. Retest verification

关闭采用可解释的 V1 规则：Defect 必须为 RESOLVED、至少有一条 evidence，且每一条关联 FAIL 所在 RunCase 的当前最新 Attempt 都必须是 attempt_no 更高的 PASS。多个 evidence 若属于同一 RunCase，一个位于所有关联 FAIL 之后的最新 PASS 可验证这些失败；若属于多个 RunCase，每个 RunCase 都必须分别通过。

因此以下情况不能关闭：只有 FAIL、FAIL 后最新结果仍为 FAIL、PASS 序号不在 evidence 之后、或 PASS 属于无关 RunCase。PASS 不自动关闭缺陷，用户仍需显式 close。关闭检查按 Run/RunCase/Attempt 锁定，和 `recordAttempt` 串行化，避免检查后同时追加结果。

## 7. Permissions

| Actor | Read/report | Edit/evidence | IN_PROGRESS/RESOLVED | Reopen/close |
| --- | --- | --- | --- | --- |
| ACTIVE ADMIN | 全项目 | 允许 | 允许 | 允许 |
| ACTIVE TESTER member | 所属项目 | 允许 | 允许 | 允许 |
| ACTIVE DEVELOPER member | 所属项目 | 不允许 | 仅自己负责的 Defect | 不允许 |
| INACTIVE member / DISABLED user | 拒绝 | 拒绝 | 拒绝 | 拒绝 |

权限复用 `ProjectAccessPolicy`；新增的 `requireProjectMemberWrite` 只表达 ADMIN 或任意 ACTIVE 项目成员这一已有身份边界，不引入第二套角色系统。归档 Project 允许读，禁止创建、编辑、证据和状态写入。

## 8. Transactions

所有 public Service 操作继续经 `ServiceTransaction → JdbcServiceTransaction → JdbcTransactionManager`。`ServiceDaos` 中的 Defect/AttemptDefect DAO 与其余 DAO 使用回调提供的同一 Connection。Service 和 DAO 均不 commit、rollback、close 外层 Connection、开启 nested transaction 或自动 retry。

创建故障注入证明 `Counter + Defect + evidence` 全部回滚。reopen 的故障注入测试在 Defect 已更新、evidence 已插入后抛出异常，最终确认状态、lockVersion 和 evidence 集合全部恢复，证明二者使用同一事务。

## 9. Optimistic locking

普通 update、状态 transition、close 和 reopen 都要求调用方提供 lockVersion。Service 在 `findByIdForUpdate` 后比较调用方版本，DAO 的 `WHERE id=? AND lock_version=?` 仍是最终边界。真实双线程测试使用同一旧版本并发更新，结果严格为一个成功、一个 Conflict，不静默覆盖。

Evidence 关系没有 lock_version；相关写入统一先锁 Defect，再对关系集合做 current locking read。close 会比较锁前候选 evidence 与锁后的当前集合，集合变化就回滚拒绝。

## 10. Lock order

本轮固定顺序为：

```text
Project → User/Membership → optional BUG Counter
        → Run → RunCase → Attempt → Defect → AttemptDefect relation
```

同类 ID 升序。create 在 Counter 后锁执行证据；add/remove/reopen 锁证据层级后锁 Defect；close 先按 ID 锁全部相关 Run、RunCase、evidence/latest Attempt，再锁 Defect 并 current-read 关系集合。普通 Defect update 只走 Project → User/Membership → Defect。

该顺序与 Round 2 `recordAttempt` 的 Project → User/Membership → Run → RunCase → Attempt 一致。测试覆盖并发 BUG 创建、并发 Defect update 和 close/retest 的数据库锁行为，但不声明所有未来 Defect/Import 组合绝对无死锁。

## 11. Tests

本轮新增 8 项 MySQL integration tests，无新增 fast unit test：

- evidence-backed 创建、Developer 报告、双向查询和 duplicate link 幂等；
- PASS/BLOCKED/SKIPPED、跨项目、INACTIVE/DISABLED actor、归档 Project 拒绝；
- BUG Counter + Defect + evidence 故障回滚；
- 第二 FAIL evidence、重复、跨项目、显式纠正且两端保留；
- 多 evidence 的 later-current-PASS close、无关 PASS/最新 FAIL 拒绝、CLOSED 只读、回归重开；
- reopen evidence 插入失败时，状态、lockVersion 和 evidence 关系全部回滚；
- Developer 仅处理自己负责的缺陷、Tester 编辑、顺序 stale lockVersion 拒绝；
- 两路并发 BUG 编号唯一和两路并发更新一胜一 Conflict。

既有 DAO tests 增加三个锁定 primitive 的真实读取断言。原 178 项全部保留，当前总数 186。

## 12. Limitations

- 当前 Service create 只开放本轮要求的 FAIL evidence-backed 缺陷；冻结模型允许零链接手工 Defect，但未增加一个无证据 public 入口。
- Defect create 没有 request idempotency 字段；HTTP 层若需要安全重试，应在未来单独设计，不靠 BUG 编号猜测请求重复。
- V1 不保存完整状态/evidence 变更审计，不增加 DefectFix/Verification 表。
- 查询返回完整列表，没有分页或 Dashboard 聚合。
- “当前最新 PASS”是 V1 close 口径；更复杂的多环境、build 或根因级验证需要未来模型扩展。

## 13. Targeted review conclusion

最终针对性 Review 已核对 Defect create、evidence、状态机、close/retest、reopen、乐观锁、权限、归档项目、事务边界、锁顺序和三个 DAO locking primitive。`DefectService` 不创建或修改 Attempt；TestExecution 与 Defect 的边界保持清晰。没有新增 Import、Automation、Servlet、REST、前端或 AI。

- A. Phase 3 Round 3：批准完成。
- B. Defect Service：批准作为后续 Servlet/API 层基础。
- C. 必须修复的问题：none。
- D. Round 3：可以 commit/push。
- E. 下一步：可以进入 Round 4，但本次 Review 未开发 Round 4。

## Findings

- CRITICAL：none。
- HIGH：none。
- MEDIUM：none。
- LOW：通用 `transition` 对返回 OPEN、绕过 `reopen` 进入 REOPENED 原先抛 `ValidationException`，与非法状态迁移应返回 `ConflictException` 的约定不一致；已最小修复并加入断言。
- INFORMATIONAL：补齐 reopen evidence 插入故障的真实事务回滚测试、CLOSED evidence add/remove 拒绝、Developer evidence/reopen 拒绝，以及非法状态跳转测试；均通过。

## Final verification

- `mvn test`：51 tests passed，BUILD SUCCESS。
- `mvn -Pmysql-tests test`：186 tests passed，BUILD SUCCESS。
- `mvn -Pmysql-tests clean package`：186 tests passed，WAR BUILD SUCCESS。
- `git diff --check`：通过。
- 集成测试仅使用 `127.0.0.1:13307/qatrack_test_r1`；清理后 test schema 为 0 tables，临时 MySQL 8.0.46 已关闭，13307 无监听。
- Windows `MySQL80` 开发服务仍为 Running；本轮未连接或修改 `localhost:3306/qatrack`。
