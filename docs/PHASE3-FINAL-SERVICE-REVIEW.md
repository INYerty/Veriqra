# Phase 3 Final Service Review

日期：2026-09-20。审计基线为 `7c98a67 feat(service): add defect lifecycle and retest workflow` 加当前未提交的 Round 4 Automation / Import 实现。本报告以实际 Java、DAO SQL、冻结 schema 和真实 MySQL 8.0.46 测试为准；没有开发 Servlet、JSON、Authentication 或前端，没有修改 schema、seed、ConnectionPool、JdbcTransactionManager 或 Maven 依赖。

## 1. Final verdict

**批准 QATrack Phase 3 Service Layer 完成。** Final Review 发现三个同源并发 HIGH，均已最小修复并由真实 MySQL 并发测试覆盖。最终状态没有 CRITICAL、未关闭 HIGH 或阻塞 MEDIUM。

分层保持为：future Servlet / API → Service interface → DAO interface → JDBC / MySQL。全部 Service 与 HTTP、Servlet、session、JSON 和状态码无关；Service 不拼 SQL、不借第二条物理 Connection、不 commit/rollback/close Connection，也不调用只存在于 JdbcXxxDao 的实现方法。

## 2. Findings

### CRITICAL

none。

### HIGH（均已修复）

1. **Automation Identity 缺行锁死锁。** 双线程在插入屏障后同时注册相同四元组，旧实现对不存在唯一键执行 `FOR UPDATE`，MySQL 8.0.46 返回 SQLState 40001 / vendor 1213。根因是 InnoDB gap lock 与后续插入意向冲突。修复为普通精确读取后插入，由 `uq_automation_identity` 裁决；仅捕获 1062，原事务退出并回滚后，在新事务中重新检查权限及读取等价 Identity。找不到等价行或其他数据库错误继续抛出。测试证明两调用得到同一 ID、数据库只有一行、不泄露 1213。
2. **Submission Key 缺行锁死锁。** 相同 submissionKey 在不同 RunCase 并发时，旧 `findBySubmissionKeyForUpdate` 对缺行加 gap lock并在插入处出现 1213。修复为普通读取加 `uq_attempts_submission` 最终裁决；1062 原事务回滚后在新事务中读取，完整载荷相同返回原 Attempt，不同载荷抛 `ConflictException`，不存在对应 key 则保留原数据库异常。恢复绝不在 aborted transaction 中查询。
3. **空 Attempt 集合 latest locking read 死锁。** 两个不同 RunCase 并发写各自第一条 Attempt 时，`findLatestByRunCaseForUpdate` 在空子范围产生 gap lock，插入阶段可返回 1213。不能简单改为普通 SELECT：前置查询已经建立 REPEATABLE READ snapshot，锁等待后普通读可能看不到最新提交；冻结 schema 也没有独立 attempt counter。最终保留 Run → RunCase → latest Attempt current-read 协议，并只在 `TestExecutionService.recordAttempt` 对 vendor code 1213 重启整个事务一次。第一次事务已经由 JdbcTransactionManager 回滚；第二次 1213 或其他错误直接上抛。DAO 不 retry，没有循环、退避配置或通用框架。测试证明不同 RunCase 首次写均成功且各为 attempt_no=1，并证明注入的连续两次 1213 只调用 insert 两次、最终 0 行、第二次异常上抛。

### MEDIUM

none。

### LOW

none。

### INFORMATIONAL

- 并发测试证明当前编排和已知间隙锁路径，不宣称系统永不发生死锁；第二次 1213 仍作为基础设施失败交给调用方。
- 用户/项目成员平台管理、Project 恢复、分页、HTTP 上传、认证和响应序列化不在本轮十个 Service 域内。未来 Servlet 不应直接调用 DAO 补这些能力；若产品页面需要，应先增加对应 Service 用例。
- 一个 JUnit import batch 只接受一个 sourceNamespace；多个 classname 必须拆批。JUnit timestamp 不映射，automated Attempt 的 executedAt 为 NULL。

## 3. Service coverage matrix

| Domain | Service | Main write operations | Transaction boundary | Permission boundary | Status |
| --- | --- | --- | --- | --- | --- |
| Project | ProjectService | create + 4 counters + optional member；update；archive | 每个 public 操作一个 Service transaction | write 仅 ACTIVE ADMIN | Complete |
| Requirement | RequirementService | REQ 分配、create、update、覆盖失效 | Counter/entity/relation 同事务 | ADMIN / ACTIVE TESTER | Complete |
| TestCase | TestCaseService | TC 分配、create + steps、update + replace steps + 覆盖失效 | Case/steps/relation 同事务 | ADMIN / ACTIVE TESTER | Complete |
| Traceability | TraceabilityService | attach、reattach、confirm、remove | 两端与关系同事务 | ADMIN / ACTIVE TESTER | Complete |
| TestPlan | TestPlanService | PLAN 分配、create/update/archive、add/remove Case | Plan/scope/version 同事务 | ADMIN / ACTIVE TESTER | Complete |
| TestRun | TestRunService | Plan / Ad-hoc Run、全部 snapshot、complete/cancel | Run/RunCase/steps 原子写；终态单事务 | ADMIN / ACTIVE TESTER | Complete |
| TestExecution | TestExecutionService | append manual Attempt | Run/RunCase/current latest/Attempt 同事务 | ADMIN / ACTIVE TESTER | Complete |
| Defect | DefectService | BUG 分配、create、edit、transition、evidence、reopen/close | Counter/Defect/evidence 或状态/重测验证同事务 | 见权限矩阵 | Complete |
| Automation | AutomationService | Identity register、map/reactivate/deactivate | 每次注册/映射变更一个事务 | ADMIN / ACTIVE TESTER | Complete |
| TestImport | TestImportService | analyze（零正式写）、Run/snapshot/Import/Attempt import | 整批一个事务；解析在事务外 | preview 可读成员；import 仅 ADMIN/TESTER | Complete |

上述核心写用例均有单一 Service 入口，不要求 Servlet 手工拼多个 Service 才获得原子性。相邻职责不重叠：Run 负责执行范围快照，Execution 只追加人工 Attempt，Import 自己原子创建导入 Run 和 automated Attempt；Defect 不创建 Attempt；Automation 不执行导入。

## 4. Transaction matrix

| Workflow | Atomic resources | Current/locking reads | Failure result |
| --- | --- | --- | --- |
| Project create | Project、4 Counter、optional Member | actor User SHARE | 任一步失败全部不存在 |
| Requirement create/update | Counter + Requirement；update + Traceability invalidation | Project/User/Member SHARE，Requirement UPDATE | 编号、实体、关系一起回滚 |
| TestCase create/update | Counter + Case + Steps；Case + Steps + Traceability | Project/User/Member SHARE，Case UPDATE | 不留空 Case、部分步骤或错误复核状态 |
| Traceability state change | Requirement、TestCase、link | 两端 UPDATE | 状态和复核主体/时间一起回滚 |
| TestPlan create/update/scope | Counter、Plan、PlanCase | Case UPDATE、Plan UPDATE、scope UPDATE | 计划、版本、范围一起回滚 |
| TestRun snapshot creation | Run、全部 RunCase、全部 snapshot step | Case UPDATE；Plan 与 scope UPDATE（如有） | 不留空 Run 或部分快照 |
| recordAttempt | Run、RunCase、latest Attempt、new Attempt | Run/RunCase/latest UPDATE | 旧历史不变；失败无新 Attempt |
| Defect create | BUG Counter、Defect、first evidence | Project/User/Member SHARE，Run/RunCase/Attempt UPDATE | 编号、缺陷、证据一起回滚 |
| Defect reopen/close | evidence hierarchy、Defect、relation | Run/RunCase/Attempt/latest/Defect/relation UPDATE | 状态和新 evidence 一起回滚 |
| Automation mapping | Case、Identity、Mapping、history probe | Case/Identity/Mapping/Attempt UPDATE | 不出现半映射或历史改绑 |
| JUnit import | Run、snapshots、TestImport、all Attempts、Run completion | Cases/Identities/Mappings UPDATE | 任一结果失败整批 0 正式数据 |

`JdbcServiceTransaction` 只把 callback 交给 `JdbcTransactionManager`；同一 `ServiceDaos` 中全部 DAO 使用 callback 的同一 Connection。RuntimeException/Error 传播，SQL failures 保持 `DataAccessException`；没有 catch 后继续提交的路径。

## 5. Global lock ordering

代码中的稳定前缀是 Project → actor User / Membership。各工作流随后按下表顺序，并对同类多 ID 升序锁定：

| Workflow family | Actual order after permission prefix |
| --- | --- |
| REQ / TC / PLAN create | Counter → entity inputs（Case 时按 ID）→ entity / children |
| Requirement / TestCase / Traceability edit | Requirement → TestCase（需要两端时）→ relation；单端更新后只触碰其 relation rows |
| Plan edit / Plan Run | TestCase IDs ascending → Plan → PlanCase rows |
| Ad-hoc Run | TestCase IDs ascending → new Run → RunCase/steps |
| recordAttempt / Run finish | Run → RunCase IDs ascending → latest Attempt |
| Defect create/evidence/reopen | participant User/Member IDs ascending → optional BUG Counter → Run IDs → RunCase IDs → Attempt IDs → Defect → evidence rows |
| Defect close | Run IDs → RunCase IDs → Attempt IDs → latest per RunCase → Defect → evidence rows |
| Mapping edit | TestCase → Identity → Mapping → Attempt history probe |
| Import | TestCase IDs → Identity IDs → Mapping → new Run/RunCase/steps → Import → Attempt |

Project archive 先锁 Project UPDATE，Run 创建/执行写先锁同一 Project SHARE，因此归档与新 IN_PROGRESS Run 不会穿插。Case edit 与 snapshot 都锁 Case；Plan edit 与 Plan Run 都是 Case → Plan；Mapping 与 Import 都是 Case → Identity → Mapping。未发现真实 AB-BA 反序。没有增加 generic lock manager 或无依据的全表锁。

## 6. REPEATABLE READ current-read audit

| Latest state required | Mechanism | Result |
| --- | --- | --- |
| Project/user/membership write eligibility | Project/User/Member `FOR SHARE` | current status/role used |
| Requirement/TestCase/Plan optimistic target | entity `FOR UPDATE` + expected lockVersion | stale update rejected |
| Plan scope after earlier snapshot | PlanCase `FOR UPDATE` after Plan lock | current set revalidated |
| Run and RunCase before Attempt | both `FOR UPDATE` | terminal/moved hierarchy rejected |
| Attempt sequence/current outcome for write | latest `FOR UPDATE` | current row seen；空集 1213 由一次全事务重试处理 |
| Defect evidence and retest | hierarchy + latest + relation `FOR UPDATE` | close uses current PASS/evidence |
| Mapping during import/edit | Identity/Mapping `FOR UPDATE` | inactive/rebind race rejected |
| Import requestKey | normal read + UNIQUE 1062 + new-transaction recovery | no absent-key gap lock |
| Automation identity tuple | normal read + UNIQUE 1062 + new-transaction recovery | no absent-key gap lock |
| Attempt submissionKey | normal read + UNIQUE 1062 + new-transaction recovery | no absent-key gap lock |

只在状态可能因先前 snapshot 过期且必须阻止并发改写时使用 locking read；只读列表继续使用普通 SELECT。

## 7. Permission matrix

所有角色和 membership/status 均从数据库读取；command 不携带 systemRole/projectRole。DISABLED actor 和 INACTIVE membership 对写操作一律拒绝。

| Operation | ADMIN | ACTIVE TESTER | ACTIVE DEVELOPER | Archived Project |
| --- | --- | --- | --- | --- |
| Project create/update/archive | yes | no | no | update/archive no-op transition rejected |
| Asset/Traceability/Plan/Run write | yes | yes | no | reject |
| Manual Attempt | yes | yes | no | reject |
| Defect report | yes | yes | yes | reject |
| Defect ordinary edit/evidence/reopen/close | yes | yes | no | reject |
| Defect OPEN/REOPENED→IN_PROGRESS、IN_PROGRESS→RESOLVED | yes | yes | assigned developer only | reject |
| Automation register/map/deactivate | yes | yes | no | reject |
| Import preview | yes | yes | yes | allowed read |
| Formal import | yes | yes | no | reject |
| Domain reads | yes | active member | active member | allowed read |

## 8. State machine summary

- Project：ACTIVE → ARCHIVED；archive 先确认无 IN_PROGRESS Run；ARCHIVED 普通写不能复活。
- Requirement：DRAFT ↔ ACTIVE，二者可 → ARCHIVED；ARCHIVED 终态只读；语义内容修改令 CONFIRMED links 进入 NEEDS_REVIEW。
- TestCase：DRAFT ↔ READY，二者可 → ARCHIVED；READY 至少一步；语义修改自动回 DRAFT；ARCHIVED 终态。
- Traceability：新建/重连为 NEEDS_REVIEW；NEEDS_REVIEW → CONFIRMED；有效态 → REMOVED；REMOVED 只能 reattach 回 NEEDS_REVIEW。
- TestPlan：DRAFT ↔ READY；独立 archive 到 ARCHIVED；范围修改递增版本并回 DRAFT；ARCHIVED 终态。
- TestRun：创建即 IN_PROGRESS，只能 → COMPLETED 或 CANCELLED；终态不 reopen。
- TestAttempt：PASS/FAIL/BLOCKED/SKIPPED 仅 insert；NOT_RUN 是没有 Attempt。
- Defect：OPEN/REOPENED → IN_PROGRESS → RESOLVED → CLOSED；RESOLVED/CLOSED 只能携带新 FAIL evidence reopen；普通 update 不接收 status。
- AutomationMapping：ACTIVE ↔ INACTIVE；ACTIVE 不直接改绑；历史已引用的 Mapping 永不改绑。

## 9. History integrity

RunCase/RunCaseStep 从锁定的当前 Case/Step 复制完整 snapshot，之后的定义编辑不触碰历史表。Attempt 和 TestImport 只有 insert/query contract；FAIL→PASS 保留两行。Mapping 被 Attempt 引用后不能更换 testCaseId，停用/同 Case 恢复不改写历史含义。Defect reopen 在原 Defect 上增加新 evidence，旧 evidence 保留；removeEvidence 仅删除错误关联，不能更新/删除 Attempt 或失败事实。TestImport replay 返回已有 Run/Attempt，不生成第二批历史。

## 10. Counter audit

REQ、TC、PLAN、BUG 全部只通过 `ProjectCounterDao.allocateNext(projectId, type)` 分配。没有 `SELECT MAX + 1`、lazy counter 初始化或 DAO retry。Counter 与实体/步骤/关系/证据处于同一 Service transaction，回滚恢复 nextValue。DAO 并发测试和 Requirement/TestCase/Plan/Defect Service 编排测试覆盖唯一编号及失败回滚。

## 11. Idempotency audit

### Manual Attempt

`RecordAttemptCommand.submissionKey` 在 Service 中必填。相同 key + 相同 runCase/status/actor/source shape/duration/comment/failureMessage 返回原 Attempt；任一载荷不同为 Conflict。重复检查在 Run 终态新增判断之前，所以已成功的旧请求仍恢复，新 key 被终态拒绝。1062 恢复只在原事务退出后使用新事务；1213 只重跑完整事务一次。

### TestImport

requestKey + raw SHA-256 + project/importer/sourceNamespace/filename/Run metadata 全部相同才 replay；不同数据为 Conflict。并发由 UNIQUE 裁决，失败事务回滚后只读恢复一次；恢复使用新的 transaction callback，不引用失败事务创建的 Run/Import 对象，不无限 retry，不吞非 1062 错误。

### Automation Identity

稳定四元组由 UNIQUE 裁决。顺序重复直接返回已有行；并发 1062 后新事务读取同一不可变四元组。其他 SQL 错误不恢复。

## 12. Automation / Import audit

未知 Identity 只进入 preview unmapped，不自动创建 TestCase、Identity 或 Mapping。preview 只有读取；正式 import 要求全部 Identity 存在、Mapping ACTIVE、目标为同项目 READY Case。Identity 四元组保持 `project + JUNIT + classname + name` 精确匹配。被历史 Attempt 使用的 Mapping 不可 rebind。普通 Run API 不能写 TestImport 或 automated Attempt source shape，因此不能伪造 IMPORT Run。一个 report 多 Identity 指向同一 Case 整批拒绝；单 batch sourceNamespace 限制由 preview/formal validation 与测试覆盖。

## 13. XML security and result mapping

`JUnitXmlParser` 使用 JDK DOM，逐项强制：secure processing、disallow DOCTYPE、关闭 external general/parameter entity、关闭 external DTD、`ACCESS_EXTERNAL_DTD/SCHEMA=""`、XInclude=false、expandEntityReferences=false，并安装拒绝型 EntityResolver。任何 feature/attribute 配置不支持会抛 `ParserConfigurationException` 并 fail closed，不降级解析。测试使用 file URI 和本机 HTTP listener，均在文件/网络访问前被拒绝。

结果映射固定为 PASS→PASS、failure→FAIL + `[JUnit failure]`、error→FAIL + `[JUnit error]`、skipped→SKIPPED；NOT_RUN 不产生 Attempt。duration 仅在可解析时转换为毫秒；没有虚构 schema 字段。failure/error/comment 按 UTF-8 字节安全截断到 MySQL TEXT 上限，不依赖数据库静默截断。nested suites 只从 testcase classname/name 构造身份，不用 suite 名污染 namespace。

## 14. Exception consistency

- 缺失/非法 caller input、跨项目参数：`ValidationException`。
- 目标不存在：`NotFoundException`。
- actor/user/membership/role 不允许：`ForbiddenException`。
- 状态机、乐观版本、幂等载荷、并发集合变化：`ConflictException`。
- SQLException、FK/CHECK/truncation、第二次 deadlock：保留为 `DataAccessException`；`OptimisticLockException` 只在 Service 边界转换为 Conflict。

`DataAccessException` 保存原 SQLException cause、SQLState 和 vendor code，消息不包含 SQL 参数。业务异常不包含 HTTP status。

## 15. Command trust boundary

Command 只提供业务输入、实体引用、expected lockVersion 和幂等 key。DB id、业务编号、attempt_no、createdAt/updatedAt、snapshot 字段、executedBy/importedBy、actor role、membership、system role 和状态机生成状态均由 Service/DB 决定。actorUserId 将由未来认证层传入，但权限始终从数据库读取。上传 byte[]、步骤列表和 Case ID 列表均 defensive copy；TestImport SHA-256 Model 同样 defensive copy。

## 16. Cross-project invariant matrix

| Invariant | DB guarantee | Service validation | Test evidence |
| --- | --- | --- | --- |
| Requirement ↔ TestCase | 两个独立 FK，不能保证同项目 | Traceability 锁两端并比较 projectId | cross-project attach rejected |
| Plan ↔ TestCase | 两个独立 FK | Plan create/add/update 锁 Case 并比较项目 | cross-project plan scope rejected |
| Run ↔ Plan | 独立 project_id / plan FK | createFromPlan 比较 projectId 并锁 Plan | cross-project Plan Run rejected |
| RunCase ↔ TestCase | Run/Case FK + Run×Case UNIQUE | Run/import 锁 Case、比较 Run project | Run creation/import integration tests |
| Attempt ↔ RunCase | FK | manual/import 只使用已锁定/新建 RunCase | hierarchy/current-run tests |
| Attempt ↔ Defect | 两个 FK，不能限制 FAIL/同项目 | Defect 锁 Run hierarchy，要求同项目 FAIL | PASS/cross-project evidence rejected |
| Automation Identity ↔ TestCase | Mapping 两 FK，不能比较项目 | map/import 比较 Identity/Case project | cross-project Mapping rejected |
| Import ↔ Mapping/Run/Attempt | 各 FK + request/import-runCase UNIQUE | formal import 锁定并复核 project/source/mapping/run source shape | full import + rollback + idempotency tests |

## 17. Rollback evidence

真实 integration tests 已证明：REQ Counter + Requirement；Requirement update + Traceability invalidate；TC Counter + Case + Steps；Case update + step replacement + traceability；PLAN Counter + Plan + Cases；Run + 第 N 个 snapshot；Attempt insert；BUG Counter + Defect + evidence；Defect reopen + evidence；Import Run + snapshots + TestImport + earlier Attempts 均在后续故障时完整回滚。新增连续 1213 测试还证明每次 transaction attempt 都结束后才重启，第二次失败不留 Attempt。

## 18. DAO primitive audit

锁定 primitive 名称与 SQL 一致：Project/User/Member SHARE；Requirement/TestCase/Plan/Run/RunCase/Attempt/Defect/Identity/Mapping UPDATE；PlanCase 和 AttemptDefect 集合 UPDATE；latest Attempt current read；Mapping history reference probe。全部使用 PreparedStatement，要求 autoCommit=false 的方法主动拒绝外层事务缺失。DAO 不判断权限、同项目、状态机或幂等载荷，不 commit/rollback/close Connection。

Final Review 移除了两个危险的“缺失唯一键 `FOR UPDATE`” primitive：Identity external tuple 与 Attempt submissionKey。新增 Round 4 primitive 只剩 `listByImport` 和 `hasAutomationMappingReferenceForUpdate`。Phase 2 的 append-only、snapshot、optimistic locking contract 未破坏。

## 19. Course requirement mapping

| Requirement | Evidence | Status |
| --- | --- | --- |
| Model | 19-table typed model/enums | Complete |
| DAO interface | 每表正式 contract | Complete |
| JDBC implementation | PreparedStatement + resource ownership | Complete |
| Own connection pool | approved ConnectionPool | Complete |
| Transaction management | JdbcTransactionManager + Service adapter | Complete |
| Service business logic | 本报告十个业务域 | Complete |
| Exception handling | business hierarchy + DataAccess metadata | Complete |
| Utility | DateTimeUtils + tests | Complete |
| Package separation | config/jdbc/model/dao/service/util | Complete |
| Integration tests | isolated MySQL fixture + rollback/concurrency/security evidence | Complete |

Service 已完成到课程后端业务层；Servlet、JSON、Authentication、frontend 尚未开始，不能声称课程全部后端/UI 已完成。

## 20. Over-design audit

依赖和源码中仍没有 Spring/Spring Boot、Hibernate/JPA、MyBatis、event bus/domain event framework、UnitOfWork、generic repository、workflow engine、retry framework、generic lock manager、message queue。1213 处理是 `DefaultTestExecutionService` 内两次显式调用上限的局部编排，不是可配置基础设施。

## 21. Final verification

| Check | Result |
| --- | --- |
| `mvn test` | 57 tests passed，BUILD SUCCESS |
| `mvn -Pmysql-tests test` | 206 tests passed，BUILD SUCCESS |
| `mvn -Pmysql-tests clean package` | 206 tests passed，WAR BUILD SUCCESS |
| `git diff --check` | PASS；仅有 Windows 工作区 LF/CRLF 提示 |
| isolated schema / server | `qatrack_test_r1` 为 0 tables；临时 MySQL 8.0.46 已关闭；13307 无监听 |

最终验证只使用 `127.0.0.1:13307/qatrack_test_r1` 专用非 root 账户，没有连接或修改 `localhost:3306/qatrack`。WAR 为 `target/qatrack-0.1.0-SNAPSHOT.war`；运行依赖仅包含 `mysql-connector-j-9.7.0.jar`，不包含测试类、integration fixture、JUnit/Surefire 或本地 properties。Windows `MySQL80` 开发服务保持 Running。

## 22. Final answers

- A. Phase 3 Service Layer：批准完成。
- B. commit 前必须修复：none；三个 HIGH 已关闭。
- C. Round 4 + Final Review：完整验证已通过，可以 commit/push。
- D. 当前 Service Layer 足以作为 Servlet / REST-ish API 的稳定业务基础；API 层不得绕过 Service。
- E. 下一阶段可进入 Servlet + JSON + Authentication，但本轮未开始这些内容。
- F. MySQL deadlock 处理仅限 `recordAttempt` 的 vendor 1213、最多一次、完整事务重启；DAO 不 retry，不是 generic framework。

## 23. Git status

`main` 与 `origin/main` 指向相同基线。Working tree 保留 Round 4 实现、并发修复、对应测试和本报告的预期未提交修改；staging area 为空。本轮没有 commit 或 push。数据库 schema、`pom.xml`、`ConnectionPool` 和 `JdbcTransactionManager` 均无修改。
