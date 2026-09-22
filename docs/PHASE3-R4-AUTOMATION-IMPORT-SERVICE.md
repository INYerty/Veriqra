# Phase 3 Round 4 — Automation Mapping and JUnit Import Service

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

日期：2026-09-20。基线提交 `7c98a67 feat(service): add defect lifecycle and retest workflow`；开始时 `main` 与 `origin/main` 同步且工作区干净。本轮只实现 Automation Mapping、JUnit XML 预览和原子导入，没有修改冻结 schema、seed、ConnectionPool、JdbcTransactionManager 或 Maven 依赖。

## 1. Automation Identity

`AutomationService` / `DefaultAutomationService` 提供 Identity 注册/取得、项目列表、当前 ACTIVE Mapping 查询、项目 Mapping 列表、映射/恢复和停用。稳定身份严格采用冻结四元组：`project_id + source + namespace + external_key`。V1 source 只有 JUNIT；JUnit parser 将 testcase `classname` 原样作为 namespace、`name` 原样作为 externalKey，使用二进制排序规则精确区分大小写，不 trim、截断或模糊匹配。

注册是显式用户操作；顺序重复先读取现有行，并发同键由数据库 UNIQUE 裁决，失败事务回滚后只做一次新事务读取恢复。Identity 没有 update/delete；分析未知结果时只返回身份键，不注册 Identity、不创建 TestCase。

## 2. Mapping lifecycle

一个 Identity 最多一条 Mapping 行，包括 INACTIVE；一个 TestCase 可被多个 Identity 映射。新绑定写 ACTIVE；ACTIVE 同 Case 的重复确认返回原行，ACTIVE 改到另一 Case 被拒绝，必须先停用。INACTIVE 可恢复；只有尚未被 Attempt 引用的 INACTIVE Mapping 才可纠正到另一 Case。

被历史 Attempt 引用后，Mapping 可以停用或以同一 Case 恢复，但不能改绑 Case，因为历史 Attempt 只保存 mapping ID，改绑会改写历史含义。校验在 Identity/Mapping 锁内使用 Attempt 当前锁定引用探针；DAO 仍不包含业务判断，不提供 hard delete。

## 3. JUnit identity format

本轮采用实际 schema 能直接表达的格式：

```text
source      = JUNIT
namespace   = testcase@classname
externalKey = testcase@name
```

同一报告内重复的精确身份是 invalid entry。`test_imports.source_namespace` 是单值，且冻结模型要求它与 Identity.namespace 一致，因此一个批次中每个 testcase 的 classname 都必须等于命令中的 sourceNamespace。跨 classname 的报告需按 namespace 拆成多个批次；本轮不修改 schema 或引入复合编码。

## 4. Parser scope

`JUnitXmlParser` 只支持课程项目需要的 `testsuite`、`testsuites`、嵌套 suite 和 `testcase`。读取 classname、name、time、failure、error、skipped：

| JUnit result | TestAttemptStatus | Extra data |
| --- | --- | --- |
| 无结果子元素 | PASS | duration_ms（如有） |
| failure | FAIL | failure_message 以 `[JUnit failure]` 开头 |
| error | FAIL | failure_message 以 `[JUnit error]` 开头 |
| skipped | SKIPPED | 原因写 comment |

schema 没有 ERROR 状态，因此 error 映射 FAIL，并以前缀保留来源差异。BLOCKED 不由 JUnit XML 生成；NOT_RUN 仍表示没有 Attempt。duration 的 JUnit 秒值换算为四舍五入毫秒，负值、非法值和溢出作为 invalid entry。

解析器限制原始 payload 最大 5 MiB、testcase 最大 10,000 项。`failure_message` / skipped comment 按 UTF-8 字节安全截断到 MySQL TEXT 的 65,535 字节以内，不拆分 Unicode code point，不依赖 MySQL 静默截断。

## 5. XML security

使用 JDK DOM API，没有第三方 XML 框架。配置包含：

- `FEATURE_SECURE_PROCESSING=true`；
- 禁止 DOCTYPE；
- 禁止 external general/parameter entities；
- 禁止 external DTD load；
- `ACCESS_EXTERNAL_DTD` / `ACCESS_EXTERNAL_SCHEMA` 为空；
- 禁用 XInclude 和 entity expansion；
- EntityResolver 对任何外部资源请求直接抛错。

malformed XML、DOCTYPE 和外部实体均在产生预览或数据库写入前拒绝。

## 6. Analyze / preview workflow

`analyzeImport` 在事务外安全解析，在只读 Service transaction 中精确查询 Identity、ACTIVE Mapping 和 READY TestCase，返回：

- mappedResults：结果、Identity、Mapping 和 TestCase；
- unmappedIdentities：Identity 缺失、无 Mapping 或 Mapping 为 INACTIVE；
- invalidEntries：XML 条目错误、namespace 不符、跨项目/非 READY 目标或同 Case 多实现冲突。

预览不注册 Identity/Mapping，不创建 Import/Run/RunCase/Attempt，也不猜测名称相似的 TestCase。调用方须显式确认映射后重新分析。

## 7. Unmapped handling

正式导入要求每项都有当前 ACTIVE Mapping。任一 unknown/inactive Mapping 使整批以 Conflict 结束。AutomationService 的注册和 map 是独立短事务；预览不会把未知结果变为正式数据。

## 8. IMPORT Run workflow

正式导入只创建一个新的无 Plan Run；本轮不向既有 Run 扩容。冻结 schema 没有 Run source 列，因此“IMPORT Run”由关联的 TestImport 和 automated Attempt 来源形状推导，普通 TestRunService 无法创建 TestImport 或带 import/mapping FK 的 Attempt。

事务步骤为：验证 ACTIVE Project 和 TESTER/ADMIN → 重新锁定/核对全部 Case、Identity、Mapping → 创建 IN_PROGRESS Run → 创建去重后的 RunCase 和步骤快照 → 插入 TestImport → 每个 RunCase 插入一个 automated Attempt → 将 Run 更新为 COMPLETED。任何一步失败全部回滚，不留下空 Run 或部分快照。

## 9. Snapshots

每个映射目标必须是同项目 READY TestCase。导入和普通 Run 使用相同字段语义复制 TestRunCase 头及连续 TestRunCaseStep；不会因为来源是自动化而绕过快照。后续 TestCase/TestStep/Mapping 修改不改变已导入 Run 的内容或 Attempt 的 mapping ID。

## 10. Attempt mapping and single implementation invariant

自动化 Attempt 使用 `executed_by=NULL`、`import_id` 和 `automation_mapping_id` 非空；上传者只保存在 TestImport.importedBy，不冒充自动化执行者。JUnit 没有可靠执行时间时 `executed_at=NULL`。新建导入 Run 的每个 RunCase 只有一条 attempt_no=1；submissionKey 从 requestKey 和 immutable Identity ID 确定生成。

数据库 UNIQUE(import_id,test_run_case_id) 和冻结 Service invariant 都要求一个批次每个 RunCase 最多一个自动化结果。同一报告中两个 Identity 映射到同一 TestCase 时整批拒绝，不能选择最后结果或自行聚合。不同实现需拆成独立报告和独立 Run。

## 11. Idempotency

SHA-256 对原始上传 bytes 计算，不包含 filename，也不做 XML canonicalization；仅空白不同也产生不同 hash。相同 requestKey 的顺序重试比较 project、importer、raw hash、sourceNamespace、filename 和 Run metadata：全部相同返回原 TestImport/Run/Attempt，不创建第二组记录；任一不同抛 Conflict。

并发相同 requestKey 不依赖“缺行 FOR UPDATE” gap lock。数据库 UNIQUE 是裁决点：失败事务完整回滚后，Service 只执行一次只读恢复并核对等价载荷；匹配则返回已提交批次，不匹配或找不到则保留原唯一冲突。真实双线程测试证明一个 Run、一个 Import、一组 Attempt 和一个 replay 返回。

## 12. Payload hash

使用 JDK `MessageDigest SHA-256`，结果固定 32 bytes，交给已有 TestImport Model defensive copy 和 DAO 宽度保护。原 XML 不入库；filename 只接受无 `/`、`\`、NUL 的安全文件名，不保存客户端路径。

## 13. Permissions

权限继续复用 ProjectAccessPolicy：ACTIVE ADMIN 和所属项目 ACTIVE TESTER 可注册 Identity、管理 Mapping、执行 Import；ACTIVE DEVELOPER 只能读取 Identity/Mapping 和执行预览，不能写 Mapping 或导入。INACTIVE member、DISABLED user 拒绝写。ARCHIVED Project 可读和预览，但不能注册、映射或导入。

## 14. Transaction boundary

所有数据库操作经 `ServiceTransaction → JdbcServiceTransaction → JdbcTransactionManager`，一个 Service 调用内的 DAO 共享同一 Connection。Service/DAO 不自行 commit、rollback、close Connection，不开启 nested transaction。解析和 SHA-256 在事务外完成；正式关系的 current revalidation 在事务内完成。

故障注入在 Run、快照、TestImport 和 Attempt 已写入后抛出 DataAccessException，确认整个事务回滚，项目下没有 Run，请求键没有 Import，也不存在可孤立的快照/Attempt。

## 15. Lock order

实际写路径为：

```text
Project → User/Membership → TestCase（ID 升序）
        → AutomationIdentity（ID 升序） → Mapping
        → Run → RunCase/steps → TestImport → Attempt → Run completion
```

requestKey 的首次查询是普通读，不持有缺行 gap lock；并发由 UNIQUE 和事务回滚后的只读恢复处理。Mapping 改绑路径为 Project → User/Membership → Case → Identity → Mapping → Attempt history probe，与 Import 的 Case → Identity/Mapping → ... → Attempt 顺序一致。Identity 注册只走 Project → User/Membership → Identity read/insert；不存在的唯一键不使用 `FOR UPDATE` gap lock。没有 generic lock manager 或无依据的额外锁。

## 16. DAO primitives

本轮只增加两个必要 primitive：

- `TestAttemptDao.listByImport`：重建幂等 replay 结果；
- `TestAttemptDao.hasAutomationMappingReferenceForUpdate`：在 Mapping 锁后以当前读禁止历史改绑。

全部使用 PreparedStatement；DAO 不做权限、同项目、状态机、幂等载荷或事务判断。

## 17. Tests and limitations

新增 6 项 fast parser tests，覆盖 PASS/FAIL/error/SKIPPED、duration、nested testsuites、invalid entries、malformed XML、文件与网络 XXE/DOCTYPE 在访问前拒绝，以及多字节 TEXT 安全截断。新增 10 项 MySQL integration tests：AutomationService 3 项、TestImportService 7 项，覆盖注册/映射/停用/恢复/未使用改绑、Identity 并发同键幂等、跨项目/权限/归档、单个及多个 unmapped 零写入、完整导入、四种 JUnit 结果、快照、顺序与并发幂等、不同 payload 冲突、同 Case 多实现拒绝、历史改绑拒绝、Case 修改后快照不变、故障回滚。

限制：只支持常见 JUnit XML，不实现 vendor 扩展、XML canonicalization、HTTP multipart、CI 调度或现有 Run 追加；一个批次只接受一个 sourceNamespace；JUnit 时间戳未映射，executedAt 保持 NULL；固定 5 MiB/10,000 项限制可在未来 API 需求明确后参数化。没有 Servlet、REST、认证、前端、Dashboard 或 AI。

## Findings

- CRITICAL：none。
- HIGH：并发相同 requestKey 与并发相同 Identity 的首版“缺行 FOR UPDATE”方案在真实 MySQL 出现 gap-lock 死锁；两者均已改为 UNIQUE 裁决、失败事务回滚后一次只读恢复，双线程测试通过。
- MEDIUM：none。
- LOW：none。

## Final verification

- `mvn test`：57 tests passed，BUILD SUCCESS。
- `mvn -Pmysql-tests test`：206 tests passed，BUILD SUCCESS。
- `mvn -Pmysql-tests clean package`：206 tests passed，WAR BUILD SUCCESS。
- Phase 3 Round 3 的 186 项全部保留；Round 4 新增 14 项，Final Service Review 再新增 6 项高价值并发与安全测试。
- `git diff --check`：通过，仅有 Windows 工作区既有 LF/CRLF 提示。
- WAR 只包含运行依赖 `mysql-connector-j-9.7.0.jar`；没有 test classes、integration fixture、JUnit/Surefire、database-test 或 `*.local.properties`。
- 集成测试仅使用 `127.0.0.1:13307/qatrack_test_r1`；清理后 test schema 为 0 tables，临时 MySQL 8.0.46 已关闭，13307 无监听。
- Windows `MySQL80` 开发服务保持 Running；未连接或修改 `localhost:3306/qatrack`。
