# Phase 2 Round 3 — Execution & Defect Persistence

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

日期：2026-09-08。开始时 main 与 origin/main 同步，工作区干净。沿用已批准的显式 Connection、事务管理、资源关闭和异常转换模式，冻结 schema 未变。

## 1. 实现范围

根包为 `io.github.lz007001cn.qatrack`。新增 6 个 immutable record、4 个 enum、6 个 DAO 接口和 6 个 JDBC 实现，共 22 个主源码文件。

| Model（model 包） | 接口（dao 包） | 实现（dao.jdbc 包） | 数据访问能力 |
| --- | --- | --- | --- |
| TestRun | TestRunDao | JdbcTestRunDao | findById、findByIdForUpdate、listByProject、listByPlan、insert、update + lockVersion |
| TestRunCase | TestRunCaseDao | JdbcTestRunCaseDao | insert、findById、findByIdForUpdate、findByRunAndCase、listByRun、listByTestCase |
| TestRunCaseStep | TestRunCaseStepDao | JdbcTestRunCaseStepDao | insert、find(runCaseId,stepOrder)、listByRunCase |
| TestAttempt | TestAttemptDao | JdbcTestAttemptDao | insert、findById、findBySubmissionKey、listByRunCase、findLatestByRunCase、findLatestByRunCaseForUpdate |
| Defect | DefectDao | JdbcDefectDao | insert、findById、findByKey(projectId,keyNo)、listByProject、update + lockVersion |
| TestAttemptDefect | TestAttemptDefectDao | JdbcTestAttemptDefectDao | add、find、existsRecord、listDefectsByAttempt、listAttemptsByDefect、remove |

新增枚举与冻结 CHECK 一致：

- TestRunStatus：IN_PROGRESS / COMPLETED / CANCELLED。
- TestAttemptStatus：PASS / FAIL / BLOCKED / SKIPPED。NOT_RUN 表示没有 Attempt 行。
- DefectStatus：OPEN / IN_PROGRESS / RESOLVED / CLOSED / REOPENED。
- DefectSeverity：LOW / MEDIUM / HIGH / CRITICAL。优先级复用 Priority。

没有实现 Automation / Import Model 或 DAO。TestAttempt 保留冻结表中的可空 importId、automationMappingId 标量引用，不引入导入行为。没有 Service、Servlet、认证、统计、前端或 AI。

## 2. 类型、查询和更新语义

BIGINT → Long；INT → Integer；SMALLINT UNSIGNED → Integer；DATETIME(6) → LocalDateTime，沿用 UTC 约定。所有字段按冻结表映射，不增加 currentStatus、latestAttemptId、步骤结果或版本表。

submissionKey 使用不可变 UUID，写为 16 字节：先 most-significant bits，再 least-significant bits，等价于 `UUID_TO_BIN(uuid, 0)`，不使用 swap 标志。真实数据库 `BIN_TO_UUID(..., 0)` 验证往返。调用方提供令牌，DAO 不生成令牌，不因唯一冲突自动返回成功；同令牌载荷一致性由未来 Service 判断。

lockVersion 复用 JdbcValues 的 Integer 范围保护。attemptNo 读取先取 long，再检查 1..Integer.MAX_VALUE；数据库允许但超出 Java Integer 的值明确转换为 SQLState 22003 的 DataAccessException。durationMs 的 BIGINT UNSIGNED 完整范围超过 Long；遵守选定映射，仅支持 0..Long.MAX_VALUE 或 NULL，Connector/J 对超出上界的读取抛 22003，已实际测试。未知时长保留 NULL，不替换成 0。

Run 项目必填、Plan 可空。update 仅写 name/environment/buildVersion/status/endedAt，保留 projectId/testPlanId/creator/createdAt。Defect update 仅写 title/description/severity/priority/status/assigneeId/resolutionNote，保留项目、编号、报告人和创建时间。二者使用 id + lockVersion 比较并递增版本；过期或不存在抛 OptimisticLockException。DAO 不校验状态迁移或完成条件。

RunCase 标题、描述、前置条件和优先级由调用者显式提供，DAO 不读取当前 TestCase 复制内容。步骤快照同样由调用者提供。两类快照只暴露插入和查询，没有普通 update/delete。当前 Case 或 TestStep 后续编辑不改变旧快照。

Attempt 只暴露插入和查询，FAIL 后 PASS 保留两行。latest 严格按 attempt_no 降序取第一行，不按 ID、executedAt 或 recordedAt 判断。普通 latest 是一致性读取，不能直接作为并发序号分配依据。

所有列表返回不可修改的完整结果：Run 按 createdAt/id；RunCase 按对端 ID；步骤按 stepOrder；Attempt 按 attemptNo；Defect 按 keyNo。尚无分页。缺陷关联双向列表返回 TestAttemptDefect 关联记录，按对端 ID 排序；existsRecord 只表示行存在。remove 只纠正错误链接，不删两端，也不因新 PASS 自动移除旧 FAIL 证据或关闭缺陷。

## 3. 外层事务和锁

所有 DAO 接收调用者的 Connection，不借连接、不 commit/rollback、不 close Connection。PreparedStatement 绑定值，Statement/ResultSet 使用 try-with-resources，SQLException 统一转换 DataAccessException。生成字段由 MySQL 返回；写入和回读应置于同一外层事务，DAO 返回对象不是提交凭证。

新增锁定读取只覆盖冻结设计所需的 Run / RunCase 与最新 Attempt。autoCommit=true 时拒绝，SQLState 25000。调用者应在同一外层事务中按既有全局锁顺序先锁 Run，再锁 RunCase，使用 `findLatestByRunCaseForUpdate` 做当前读取，再检查整数上界、分配下一个序号并插入 Attempt。这里不新增完整重测业务方法。

父锁不可省略：没有 Attempt 时，没有稳定的 Attempt 行可作为该执行项的互斥点。普通 SELECT 在 REPEATABLE READ 的旧快照中可能看不到其他事务刚提交的 Attempt；仅锁父行不会刷新旧的一致性读视图，所以分配路径使用显式当前锁定读。真实测试在先建立空读视图后提交第一条 Attempt，验证普通 latest 仍为空、锁定 latest 能看到新行。

4 个线程各提交 4 次尝试，按调用约定获得 1..16 无重复序号。另用两个未协调的事务同时插入序号 1，验证一方成功、一方收到 UNIQUE 1062，最终只有一条记录。DAO 不自动重试，锁顺序约定也不是所有未来工作流无死锁的证明。

事务可行性实测：

- Run + 两个 RunCase + 各两个步骤可整体提交。
- 第二个快照后追加重复步骤，整个新 Run、两条快照和四条步骤全部回滚；已有当前 Case 保留。
- Attempt 后续插入失败，当前事务的尝试全部回滚。
- BUG Counter 分配 + Defect + Evidence Link 在一个外层事务中提交；证据 FK 失败时 Defect 和 Counter 递增一起回滚。

## 4. Service invariant 与边界

本轮没有改变冻结业务规则。以下缺口通过真实隔离库写入再次确认，探针全部主动回滚：Run–Plan、RunCase–Case、Attempt–Defect 跨项目关联可以通过 FK；PASS 也能被数据库关联到 Defect。因此 DAO 调用成功不能表示业务合法。

未来 Service 仍须保证：

- 同项目、用户/成员/负责人资格、项目可写状态与权限。
- 创建快照时锁定当前定义及步骤，核对版本、READY、非空范围和连续步骤，并原子创建完整快照。
- Run 终态不可 reopen、不可追加尝试，完成检查和提交尝试协调同一 Run 锁。
- 快照及 Attempt 不可覆盖，Run 项目/来源不可迁移；数据库 RESTRICT 不禁止直接 SQL 更新叶子行。
- 只有 FAIL 可以新增缺陷证据；缺陷状态流转和负责人变化规则。
- Attempt 序号在父锁内分配，提交令牌载荷一致性；事务失败传播至外层回滚，不吞错后提交。
- importId/mappingId 的同 Run/同 Case 等冻结规则仍在未来导入阶段实现，本轮仅验证人工来源及非法来源形状，未覆盖有效自动化导入链。

现有课程级边界继续保留：列表未分页；错误 cause 可能含数据库值；直接 SQL 可绕过历史不可变约定；autoCommit=true 下多次 DAO 调用不具备整体原子性。本轮没有发现需要修改 schema、连接池或事务管理器的错误。

## 5. Fixture 与验证

MysqlFixture 仅增加六张表的 DELETE 清理顺序：test_attempt_defects → test_attempts → defects → test_run_case_steps → test_run_cases → test_runs，再执行既有资产表清理。保留全部误连接防护、专用账号、空库检查、实际 catalog 校验、命名锁和仅清理本次成功 CREATE 的表的逻辑。未关闭 FK 检查，未加载 seed。

新增 ExecutionFixture 为测试数据构造器，7 个测试类共 27 项真实 MySQL 测试：Run 4、RunCase 2、快照步骤 2、Attempt 8、Defect 3、证据关联 3、跨 DAO 事务 5。原有 94 项保留，总数 121；默认不启用 mysql-tests 时仍是 39 项。

| 最终验证 | 实际结果 |
| --- | --- |
| mvn test | 39 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -Pmysql-tests test | 121 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -Pmysql-tests clean package | 121 tests，0 failures/errors/skipped，WAR BUILD SUCCESS |
| WAR 内容 | 包含六组新增 Model/DAO；唯一运行库为 mysql-connector-j-9.7.0.jar；无 local properties、IDEA 配置或测试 Fixture |
| git diff --check | PASS；另检查全部新增文件的 UTF-8、尾随空白和本机身份信息 |
| 隔离实例 | MySQL 8.0.46，127.0.0.1:13307/qatrack_test_r1；结束时 0 表，正常 shutdown，13307 无监听 |

未连接开发库 localhost:3306/qatrack，未修改 database、pom、ConnectionPool、JdbcTransactionManager 或原有 DAO。未 commit/push。

实际命令结果、WAR 检查和实例关闭证据记录在本地忽略目录 `docs/verification/phase2-r3/`。该目录可能含本机路径，不提交公开仓库。

推荐先审查 JdbcTestAttemptDao、JdbcTestRunCaseDao、JdbcDefectDao、ExecutionTransactionIntegrationTest、MysqlFixture，并结合 TestAttemptDaoIntegrationTest 阅读并发证据。
