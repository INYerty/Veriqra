# Phase 2 Round 2 — 最终针对性 Review

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

日期：2026-09-07。重新读取目标 DAO、接口、MysqlFixture、安全测试、关系/步骤/计数器集成测试、事务管理器、连接池相关归还路径及 pom.xml，并检查实际 git status。

结论：**批准修正命名后的 Round 2 持久化模式。没有未修复阻塞问题。** 本轮仅作两处 API 语义澄清，未扩展功能。

## 发现及最小修复

- CRITICAL：none。
- HIGH：none。
- MEDIUM：ProjectMemberDao.exists / TestCaseRequirementDao.exists 容易被后续 Service 误读为有效成员/有效关联判断。原 SQL 行为符合行存在语义，尚无 Service 误用，但这是应在形成调用依赖前消除的接口歧义。已将接口、实现、全部调用点统一改成 **existsRecord**，明确包括 INACTIVE / REMOVED，没有保留容易继续被误用的旧名称别名。
- LOW：none；未做风格重构。

existsRecord 返回 true 只代表对应复合键的行存在。ACTIVE 成员也不自动表示允许操作：用户/项目状态和角色仍需 Service 校验。追溯行即使 CONFIRMED，也不自动表示有效覆盖，还涉及需求/用例状态与项目一致性。

保留 TestPlanCaseDao.exists：其关系物理移除，不存在 INACTIVE/REMOVED 行与当前关系相混淆的问题；该方法仍不是项目权限或计划状态检查。

补强现有测试断言：成员行不存在为 false，ACTIVE/INACTIVE 均为 true；追溯行不存在为 false，NEEDS_REVIEW/CONFIRMED/REMOVED 均为 true。没有为测试数量新建重复测试，总数仍 94。

## 其余重点结论

| 范围 | 判断 |
| --- | --- |
| Counter | allocateNext 在 getAutoCommit=true 时失败；FOR UPDATE 锁定指定项目×类型的既有行，递增在同一连接和外层事务内，DAO 不 commit/rollback。缺行/溢出明确失败。并发 24 次分配、最新锁定读、分配与实体写入整体回滚测试继续通过。无需修改。 |
| TestStep 重排 | 当前 DAO 没有内部提交。按约定锁定父用例，在一个 JdbcTransactionManager 回调中更新父版本、删除旧步骤、添加新步骤，可以全成或全回滚；重复步骤号测试验证旧步骤及父版本恢复。**这不是 DAO 对任意调用方式的保证**：autoCommit=true 下逐步调用，或调用者吞掉失败后提交，仍可能形成部分结果。保持已批准的外层事务约定，不新增业务重排方法或暗中事务。 |
| 关系 DAO | PreparedStatement、资源关闭、SQLException 转换和连接所有权正确；没有权限、跨项目、归档或内容变化自动复核规则。markRemoved 用一条 UPDATE 写 REMOVED 并清空复核字段，是冻结关系状态的持久化形状，不是业务工作流。成员退出保留原行与 joinedAt，更新使用 lockVersion。 |
| Fixture URL | 应用配置与测试配置独立；只接受 loopback、显式端口、qatrack_test_* 且无参数的 URL。localhost:3306/qatrack 在连接前即被拒绝，安全测试不需要真实连接开发库。端口 3306 本身不是危险判定：3306 上的专用 qatrack_test_* 可以被配置使用，但 qatrack 不能。 |
| Fixture 清理 | 启动先核对实际 catalog、版本、命名锁和空 schema；仅登记成功 CREATE，DROP 使用限定的 test schema.table 且重新核对 catalog。resetRows 从固定测试池借连接，池恢复其初始 catalog，按 FK 顺序清理，不加载 seed、不关闭 FK 检查。当前调用路径未发现误清理开发库的入口。保持专用 schema 最小权限账户约定；此结论不代表允许任意修改 fixture、SQL 文件或账户权限后的无条件安全证明。 |

Counter、TestStep、MysqlFixture、ConnectionPool、JdbcTransactionManager、pom 和冻结 schema 均未修改。

## 最终验证

| 命令 | 结果 |
| --- | --- |
| mvn -B -ntp test | 39 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -B -ntp -Pmysql-tests test | 94 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -B -ntp -Pmysql-tests clean package | 94 tests，WAR BUILD SUCCESS |
| git diff --check | PASS；本轮新增/修改文本额外检查空白 |

仅使用既有独立 MySQL 8.0.46 `127.0.0.1:13307/qatrack_test_r1`。启动前检查 admin 与 fixture 配置的目标地址，无应用库连接配置复用。验证后专用 schema 为 0 张表，临时实例正常关闭，13307 无监听，原 MySQL80 服务仍 Running。未连接或修改 localhost:3306/qatrack，未修改任何 database 文件。WAR 不包含 local properties。

完整证据见 [results.json](verification/phase2-r2-final-review/results.json) 及同目录 Maven 日志。历史 Round 2 验证产物保留，不覆盖为本次结果。

## 文件范围

修改 6 个 Java 文件：ProjectMemberDao、JdbcProjectMemberDao、TestCaseRequirementDao、JdbcTestCaseRequirementDao，以及两者原有 IntegrationTest。同步 Round 2 说明中的接口名；新增本审查报告与验证记录。

实际工作区已有 Round 1 / Round 2 的未跟踪源文件和文档，README/pom 的既有修改保留。本轮变更清单与最终 git 输出见 [worktree.txt](verification/phase2-r2-final-review/worktree.txt)。没有新模块、Service、Servlet、TestRun、Defect、Automation；没有 commit/push。
