# Phase 3 Round 1 — Service Foundation

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

日期：2026-09-16。基线提交 `2474755 feat(backend): complete JDBC persistence foundation`，开始时 `main` 与 `origin/main` 同步且工作区干净。本轮只实现 Project、Requirement、TestCase、Traceability 的业务层，不修改冻结 schema、seed、ConnectionPool、JdbcTransactionManager、Maven 依赖或其他业务模块。

## 1. Service architecture

调用方向为未来 Servlet → Service interface → Default Service → DAO interface → JDBC/MySQL。四个接口及实现：

| Interface | Implementation | 职责 |
| --- | --- | --- |
| ProjectService | DefaultProjectService | 创建、读取、修改、归档项目；创建时初始化四类 Counter 和可选初始成员 |
| RequirementService | DefaultRequirementService | REQ 编号原子分配、需求读写、内容变化后追溯失效 |
| TestCaseService | DefaultTestCaseService | TC 编号、当前定义和步骤原子写入、READY 最小约束、快照隔离 |
| TraceabilityService | DefaultTraceabilityService | attach/remove/reattach/confirm 及 active/confirmed 业务判断 |

没有 IoC 容器。构造器注入 `ServiceTransaction`、`ServiceDaoFactory`、`ProjectAccessPolicy`；追溯服务另注入 `Clock`，避免复核时间在测试中不确定。`JdbcServiceTransaction` 只是已批准 JdbcTransactionManager 的适配器。`JdbcServiceDaoFactory` 在回调的同一 Connection 上创建非 owning DAO，`ServiceDaos` 明确一组 DAO 共享一个事务。

输入使用七个小型 record：Create/UpdateProjectCommand、Create/UpdateRequirementCommand、Create/UpdateTestCaseCommand、TestStepInput。调用方不能填业务编号、createdAt、数据库 ID（更新目标除外）或生成版本；更新命令只携带用户看到的 lockVersion。

## 2. Exception model

`BusinessException` 是不依赖 Servlet/HTTP 的业务异常基类，子类仅有：

- ValidationException：null、blank、VARCHAR 超长、步骤不连续等可提前识别的输入错误。
- NotFoundException：目标业务对象不存在。
- ForbiddenException：actor 不可用、禁用、无成员资格或角色不足。
- ConflictException：归档只读、非法状态、重复业务关系、乐观锁冲突等当前状态冲突。

DataAccessException 仍表示数据库或 JDBC 失败，不包装成“验证失败”。OptimisticLockException 在 Service 边界一致转换为带原 cause 的 ConflictException；其他持久化错误保留原异常。Project key 的可预见重复在查询和数据库 UNIQUE 两层映射为 Conflict，未吞掉其他 1062。

## 3. Transaction ownership

所有 public Service 操作通过 ServiceTransaction 执行。提交、回滚、Connection.close 仍只由 JdbcTransactionManager 完成；Service/DAO 都不手写 commit、rollback、setAutoCommit 或申请第二条连接，也没有 nested transaction 或自动 retry。

写入锁顺序遵守冻结约定：Project → actor User / Membership → Counter → Requirement → TestCase → relation。项目业务写先 `ProjectDao.findByIdForShare`，项目归档/修改用 `findByIdForUpdate`。写权限检查用 `UserDao.findByIdForShare` 和 `ProjectMemberDao.findForShare`，使已验证的 actor 状态/角色在事务内稳定。Project 创建时父项目尚不存在，先锁 ADMIN 用户，再创建项目。

为 Service 增加的最小 persistence primitive：

1. Project、User、ProjectMember 的事务锁定读；都拒绝 autoCommit=true。
2. TestCaseRequirementDao 按 Requirement 或 TestCase 将当前 CONFIRMED 批量改为 NEEDS_REVIEW；SQL 只匹配 CONFIRMED，所以 REMOVED 永不被重新激活。

DAO 仍不包含权限、状态机、同项目判断或事务所有权。

## 4. Permission model

actor 只使用数据库 user ID。角色和状态从 users/project_members 读取，不接受 username、前端 role 或 isAdmin。

| 能力 | ACTIVE ADMIN | ACTIVE TESTER member | ACTIVE DEVELOPER member | INACTIVE / DISABLED |
| --- | --- | --- | --- | --- |
| 创建/修改/归档项目 | 允许 | 拒绝 | 拒绝 | 拒绝 |
| 读取项目和本轮资产 | 全局允许 | 所属项目允许 | 所属项目允许 | 拒绝 |
| 创建/修改 Requirement/TestCase/Traceability | 全局允许 | 所属 ACTIVE 项目允许 | 拒绝 | 拒绝 |

ADMIN 不需要项目成员行。归档项目保持只读，ACTIVE 成员仍可读取。项目成员增删改管理仍属于后续管理用例，本轮只创建可选初始成员并提供统一访问策略。

## 5. Project rules

CreateProjectCommand 校验 key/name。key 必须以 A-Z 开头且只含 A-Z/0-9，最多 16 字符；name 非空且最多 160 字符。只有 ACTIVE ADMIN 可以创建。事务内插入 ACTIVE Project、REQ/TC/PLAN/BUG 四行 nextValue=1，并在命令同时给出 memberId/role 时验证目标用户 ACTIVE 后插入 ACTIVE member。任一步失败全部回滚。

update 只修改 name/description，保留 key、creator、状态和生成字段，并使用 lockVersion。ARCHIVED 项目不能普通修改。archive 仅允许 ADMIN；锁 Project 后确认没有 IN_PROGRESS Run，再写 ARCHIVED。没有隐式取消 Run、级联删除或本轮恢复 API。

## 6. Requirement rules

create 先锁并检查 ACTIVE Project，再锁 actor/membership，调用现有 `ProjectCounterDao.allocateNext(projectId, REQ)`，插入 DRAFT Requirement。没有 MAX+1、DAO commit 或懒建 Counter。

update 先读取目标以定位 Project，再按 Project → actor/member → Requirement 锁定并复核。ARCHIVED Requirement 终态只读；DRAFT、ACTIVE 之间可切换，也可归档。title/description 是本表实际存在的测试语义内容；二者任一变化时，同一事务将该 Requirement 当前 CONFIRMED links 批量改为 NEEDS_REVIEW。priority、status、updatedAt、lockVersion 单独变化不触发内容复核。REMOVED links 不匹配批量 SQL，保持 REMOVED。

## 7. TestCase rules

create 校验 title/priority 和显式步骤列表。DRAFT 允许空步骤；非空列表的 stepOrder 必须严格为 1..N，action/expectedResult 非空。事务内锁 ACTIVE Project/权限、分配 TC Counter、插入 DRAFT Case 和全部当前步骤。

update 锁 Project/权限/Case，使用 lockVersion；ARCHIVED Case 终态只读。请求 READY 时至少一个步骤。title、description、preconditions 或步骤内容/顺序变化属于语义变化：持久化状态自动回到 DRAFT（显式归档除外），替换全部当前步骤，并将相关 CONFIRMED links 改 NEEDS_REVIEW。只有内容未变时才可从 DRAFT 提升 READY。priority 变化本身不令覆盖失效。

步骤替换在父 Case 锁和同一事务内执行 delete + ordered inserts，中间失败恢复 Case、版本和原步骤。Service 只操作 test_cases/test_steps；没有读取或修改 test_run_cases/test_run_case_steps，真实测试确认历史快照不变。

## 8. Traceability rules and state machine

所有写操作先普通读取端点定位项目，再锁 Project、actor/member、Requirement、TestCase，锁后重新核对同项目和归档状态。

```text
不存在 --attach--> NEEDS_REVIEW --confirm--> CONFIRMED
                     |                         |
                     +--------remove----------+
                                               v
                                            REMOVED
REMOVED --attach/reattach--> NEEDS_REVIEW
```

新关系不直接 CONFIRMED。remove 写 REMOVED 并清空 review 字段，保留 first linkedBy/linkedAt；重复 remove 幂等返回现有 REMOVED。reattach 复用同一物理行并进入 NEEDS_REVIEW。confirm 只接受 NEEDS_REVIEW，复查同项目、项目 ACTIVE、两端未归档，写 actor 和注入 Clock 的 UTC LocalDateTime。

`isActiveLink` 表示物理行存在且状态不是 REMOVED；`isConfirmedLink` 只表示 CONFIRMED。两者不复用 existsRecord 的“任何物理行”含义，跨项目坏数据也不会被视为有效业务关系。

## 9. Material-change and cross-project semantics

Requirement 的 title/description 变化，以及 TestCase 的 title/description/preconditions/步骤变化，会使相关 CONFIRMED 变为 NEEDS_REVIEW。技术字段变化不触发。当前冻结模型没有 acceptanceCriteria 列，因此没有虚构该输入。

Requirement.projectId 与 TestCase.projectId 必须相同；普通 FK 无法保证，Service 在写锁前后各检查一次。跨项目 attach 即使 actor 同时是两个项目 TESTER 也拒绝。数据库仍作为实体存在、UNIQUE、CHECK 和 FK 的最终边界；Service 不复制所有 CHECK。

## 10. Tests

未引入 Mockito；轻量 fake 只用于无数据库的策略/验证测试，真实事务组合使用隔离 MySQL。

| 类型 | 新增 | 覆盖 |
| --- | ---: | --- |
| fast unit | 4 | Unicode VARCHAR 长度、步骤连续性、ADMIN bypass、TESTER/DEVELOPER/INACTIVE 权限 |
| MySQL integration | 15 | Project 3、Requirement 4、TestCase 5、Traceability 3 |
| 合计增量 | 19 | 原 143 + 19 = 162 |

三个要求的故障路径使用真实 MySQL 和外层 JdbcTransactionManager，并以 connection-scoped fault delegate 在指定数据库写入后抛错：

1. REQ Counter 已递增且 Requirement 已插入后失败，事务结束后 Requirement 0 行、nextValue 恢复 1。
2. Requirement 已更新、CONFIRMED 已改 NEEDS_REVIEW 后注入失败，标题/版本和 CONFIRMED 一起恢复。
3. TC Counter、Case、Step 1、Step 2 已写后注入失败，Case/Steps 全部不存在、nextValue 恢复 1。

另覆盖项目创建/更新/归档、归档写拒绝、非 ADMIN 管理拒绝、ACTIVE/INACTIVE membership、DEVELOPER 写拒绝、业务编号顺序、乐观冲突、READY 空步骤拒绝、非语义字段不触发追溯失效、步骤替换中途失败恢复 Case/旧步骤/CONFIRMED 关系、快照不变、跨项目 attach、remove/reattach/confirm、REMOVED 不可直接 confirm、重复 remove 幂等、物理存在与有效关系区别、归档端点确认拒绝。

## 11. Known limitations

- 本轮不提供 Project 恢复、成员管理命令或完整 RBAC；冻结角色足以实现本轮最小边界。
- 读取方法返回完整列表，未分页；异常尚未映射 HTTP，因为 Servlet 不在本轮。
- 归档项目只检查当前 IN_PROGRESS Run；未来新增 Run 状态时必须同步审查终态判断。
- Service 不自动 retry 死锁/唯一冲突。当前锁定和测试证明指定编排，不构成全部未来业务无死锁保证。
- TEXT 长度不在 Service 以 Java 字符数模拟 MySQL 字节上限；数据库仍是最终边界。VARCHAR 使用 code point 数提前校验。
- Requirement/TestCase 完整内容历史仍不保存；执行快照提供已冻结的运行历史。
- 用户/成员管理 Service、计划、执行、缺陷、自动化、导入等 Service invariant 仍未实现。

## 12. Next round boundaries

本轮未实现 TestPlanService、TestRunService、TestExecutionService、DefectService、ImportService、AutomationService、Servlet、REST/Jackson、Session/Login、密码哈希、前端、Dashboard、AI 或 JUnit XML parser。下一轮应在提交并 review 本轮后单独确定范围，继续复用 ServiceTransaction、ServiceDaoFactory 和 ProjectAccessPolicy；不要在 Servlet 复制权限或事务规则。

## 13. Verification

验证只使用 MySQL 8.0.46 `127.0.0.1:13307/qatrack_test_r1`，专用非 root 测试账户；未连接或修改 localhost:3306/qatrack。原始日志位于忽略目录 `docs/verification/phase3-r1/`。

| Check | Result |
| --- | --- |
| mvn test | 51 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -Pmysql-tests test | 162 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -Pmysql-tests clean package | 162 tests，WAR BUILD SUCCESS |
| git diff --check | PASS |
| WAR | 包含四组 Service interface/implementation；唯一运行库 mysql-connector-j-9.7.0.jar；无测试类/local properties/IDEA 配置 |
| isolated MySQL | 最后一次 package 后 qatrack_test_r1 为 0 表；mysqladmin 正常关闭，PID 结束，13307 no listener |

## 14. Review findings

- CRITICAL：none。
- HIGH：none。
- MEDIUM：none。审查中发现写权限读取需要锁住 User/Membership，已增加 FOR SHARE primitive 并由所有写 Service 使用，问题在本轮关闭。
- LOW：none。保留第 11 节明确边界，不为风格统一扩大抽象或状态机。

本轮最终结论：批准 Phase 3 Round 1 当前范围。没有修改 schema/seed、ConnectionPool、JdbcTransactionManager 或 pom；没有实现 Round 2 模块；没有 commit/push。

## 15. Final targeted review

2026-09-16 最终针对性审查逐项复核权限、Project 归档、Requirement/TestCase 语义更新、Traceability 状态机、异常边界、事务所有权、锁顺序和新增 DAO primitive。结论如下：

- CRITICAL：none。
- HIGH：none。
- MEDIUM：none。
- LOW：none。审查发现的测试证据缺口已关闭：新增 TestCase 步骤替换中途失败的真实 MySQL 回滚测试；补充非语义字段不触发追溯失效、REMOVED 不可直接 confirm 和重复 remove 幂等断言。生产实现无需修改。
- INFORMATIONAL：项目归档通过 Project `FOR UPDATE` 与 IN_PROGRESS Run 查询建立边界；未来 Run Service 创建或改变 Run 状态时，必须先按全局顺序取得同一 Project 锁并在锁后复查 ACTIVE，才能与归档并发正确协调。当前尚无 Run Service，因此这是一项 Round 2 实现约束。现有角色只有平台 ADMIN 与项目 TESTER/DEVELOPER，权限粒度受冻结模型限制。当前锁顺序和测试证明已审查路径，不能解释为所有未来组合事务的无死锁证明。

最终验证仍只使用 MySQL 8.0.46 `127.0.0.1:13307/qatrack_test_r1`。最后检查 schema 为 0 tables，临时实例已关闭，13307 无监听；未连接 `localhost:3306/qatrack`。
