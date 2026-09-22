# Phase 2 Round 3 — 最终针对性 Review

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

日期：2026-09-08。范围为执行/快照/缺陷 DAO 与接口、对应 Model、集成测试、ExecutionTransactionIntegrationTest、MysqlFixture、安全测试，以及直接依赖的事务管理器、Counter、JdbcValues、pom 和冻结数据库定义。

本次从实际工作区重新读取：Round 3 文件仍未提交；没有把上一轮 121 项通过直接当成本次验证结果。审查未发现需要修改实现的明确 bug，不作接口重命名、风格重构或无依据的锁扩展。

## 1. 严重程度

| 分类 | 问题 | 阻塞 | 修复 |
| --- | --- | --- | --- |
| CRITICAL | none | 否 | 无 |
| HIGH | none | 否 | 无 |
| MEDIUM | none | 否 | 无 |
| LOW | none | 否 | 无 |

以上表示本次限定范围内没有确认的问题，不代表所有未来 Service 工作流已完成验证。

## 2. 逐项结论

| 文件 / 范围 | 审查结论 |
| --- | --- |
| JdbcTestAttemptDao / TestAttemptDao | 只有插入与查询，没有普通 update/delete、内部编号分配或 retry。FAIL/PASS 历史分别保留。UNIQUE(runCaseId,attemptNo) 和 UNIQUE(submissionKey) 错误保留为 DataAccessException。 |
| latest 查询 | latest 表示该连接读视图中最大 attempt_no 的行，不按 ID 或 executed_at；普通查询允许 REPEATABLE READ 的旧视图。ForUpdate 变体是当前锁定读，要求外层事务和先锁 Run、再锁 RunCase。旧快照测试验证二者区别。 |
| UUID | ByteBuffer 默认大端，固定写入两个 long 共 16 字节，与 BIN_TO_UUID(...,0) 实测一致。UUID 对象不可变，没有共享可变 byte[]。null 写为 SQL NULL，由 NOT NULL 拒绝；null 查询不匹配任何行。读取校验 null/长度后才解码，未发现 NPE 或字节序问题。现有往返测试覆盖真实数据库；null 分支结论来自代码和冻结约束审查，本轮未新增该分支测试。 |
| JdbcTestRunCaseDao | 8 个字段完整对应冻结表；内容由调用者显式提供，不 JOIN 当前定义来替换快照。无 update/delete。Run×Case 由数据库 UNIQUE 保证；listByTestCase 返回历史 RunCase 记录，包含 Run ID 和 capturedAt，不返回当前 TestCase。 |
| JdbcTestRunCaseStepDao | 4 字段，复合键为 runCaseId + stepOrder；SMALLINT UNSIGNED 映射 Integer，按 stepOrder 排序。没有当前 test_steps FK，也没有 update/delete；已有替换当前步骤的测试验证快照不变。 |
| JdbcDefectDao | update 不写 projectId/keyNo/reporterId/createdAt；id + lockVersion 比较并递增，沿用 JdbcValues 范围保护。状态作为 enum 持久化，CHECK 保证说明字段形状，没有状态机或 BUG 编号分配。编号与写入仍由外层事务组合。 |
| JdbcTestAttemptDefectDao | existsRecord 只表示行存在，双向列表的接口注释和返回类型明确为关联记录。remove 符合 DOMAIN-MODEL 第 6.5 节允许纠正错误链接的设计，仅删关系，不删 Attempt/Defect，也不因新 PASS 清理旧 FAIL 证据。无接口重命名必要。 |
| 证据业务合法性 | DAO 未检查 FAIL 或同项目；真实测试确认 PASS + 跨项目链接可被数据库接受，再主动回滚。二者继续属于 Service invariant，不能因 DAO 返回成功而视为合法业务。 |
| DAO 资源 / 事务 | PreparedStatement 绑定参数；Statement/ResultSet 使用 try-with-resources。DAO 不借连接，不 commit/rollback/close 外层 Connection。写后回读应保持外层事务；不可吞掉应导致回滚的错误。 |
| ExecutionTransactionIntegrationTest | 实际创建 Run、两条 RunCase、各两条步骤后，对第二条快照插入重复步骤触发数据库错误；在新的事务中断言 Run/两条快照/全部步骤不存在，已有 Case 保留。证据是后续快照步骤失败，没有夸称测试了任意故障点。另验证整体成功、BUG Counter + Defect + Link 成功与 FK 失败回滚。 |
| MysqlFixture | 六张表按子表到父表顺序清理，保留 FK 检查。当前 Automation/Import 表无测试数据；后续阶段若填充，需届时按依赖扩展顺序。URL 在连接前拒绝 qatrack 开发库，仍检查 loopback、显式端口、test schema、实际 catalog、空库和所有权。没有扩大 suppression。 |

## 3. 并发证明的范围

冻结文档第 7 节明确允许在父执行项锁内分配 max(attempt_no)+1。当前 SQL 使用降序 LIMIT 1 的锁定查询，调用方取 attemptNo + 1；语义仍是锁内递增最新序号，没有无锁 SELECT MAX + 1，也没有新增独立 Attempt Counter。既有 ProjectCounter 仍用于 BUG 业务编号。

提交已有 Run 的路径按 Run → RunCase → Attempt 顺序；DAO 不暗中取得反向父锁。4 线程测试证明同一 RunCase 下按约定进行的 16 次提交得到不重复序号；未协调的两个提交由 UNIQUE 保留一条、拒绝另一条。不能据此宣布所有多对象工作流无死锁。创建新父/子行的测试用于证明事务原子性，不替代未来 Service 对既有 Project/Case/Plan 集合的完整锁顺序及状态校验。

## 4. 非阻塞后续建议

未来实现 Service 时再补“完成 Run 与追加 Attempt 竞争”、快照创建与当前定义编辑竞争、同提交令牌不同载荷拒绝等工作流测试。当前尚无这些 Service，不把缺少工作流实现列为本轮 DAO 缺陷。

保留已说明的边界：列表未分页、Java signed 数值上界、直接 SQL 可绕过历史不可变规则，以及 autoCommit=true 多次调用无法整体回滚。没有因此修改 schema 或已批准基础设施。

## 5. 验证和文件范围

本轮不修改任何 Java、测试、schema、pom 或已有 Round 3 说明；仅新增本审查报告。重新运行的 Maven 日志、最终状态和实例关闭证据保存于本地忽略目录 `docs/verification/phase2-r3-final-review/`，不提交公开仓库。

| 本次重新执行 | 结果 |
| --- | --- |
| mvn test | 39 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -Pmysql-tests test | 121 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -Pmysql-tests clean package | 121 tests，0 failures/errors/skipped，WAR BUILD SUCCESS |
| git diff --check | PASS；另检查全部未跟踪文本的尾随空白 |
| 文件校验 | 全部本轮开始时的 src/database/pom 及 Round 3 说明内容哈希不变 |
| 隔离实例 | MySQL 8.0.46 / 127.0.0.1:13307，qatrack_test_r1 最终 0 表；正常关闭，13307 无监听 |

没有新增或修改测试，最终总数仍为 121。未连接或修改 localhost:3306/qatrack。当前工作区仍保留上一轮未提交的 31 个新增文件及 MysqlFixture 修改，本轮额外新增本报告；没有暂存、commit 或 push。

**批准 Round 3 当前持久化层。没有必须修复的阻塞问题，建议完成 commit 后进入 Round 4；本次未开始 Round 4 开发。**
