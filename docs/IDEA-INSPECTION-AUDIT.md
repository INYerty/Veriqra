# IDEA Inspection / 提交前分析审计

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

日期：2026-09-08。范围为用户列出的 SQL 表解析、Fixture 清理、Markdown 代码片段及 Java 常规提示；没有推进 Round 3。

**结论：本轮核对范围内，真实 correctness 错误 0；IDE 上下文/已审查非阻塞告警归为 4 类。** 没有拿到完整 IDEA Inspection 导出，4 是类别数量，不是工具告警总条数；表解析类别涉及用户列出的 12 个文件。没有运行 IDEA 完整提交前分析，因此不声称 IDE 已达到 0 warnings，也不将未提供的其他告警一律视为误报。

## 1. 分类清单

### CRITICAL

none。

### HIGH

none。

### MEDIUM

none。未发现需要修改 SQL、事务、连接池或数据库模型的 correctness 问题。

### LOW：常规提示，不作为 correctness 缺陷

| 文件 / 范围 | Inspection 内容 | 是否真实问题 | 处理 / 是否修改 |
| --- | --- | --- | --- |
| ConnectionPool.java 的代理分派等条件分支 | if 可改 switch | 纯风格建议；顺序分派中 close/isClosed 与 checkOpen 有先后语义 | 保留，未修改 |
| MysqlFixtureSafetyTest.java、FakeJdbc.java 的代理 lambda | block lambda 可改 expression lambda | 纯风格建议，现有返回/抛错逻辑成立 | 保留，未修改 |
| PHASE2-JDBC-FOUNDATION.md 中 users/projects 示例变量；测试中的资源句柄 | unused variable | 示例刻意只展示共享连接；资源变量即使不被读取，也可能负责借入/归还等副作用。用户未提供其余具体行号，不能据此批量删除 | 保留，未修改 |
| ConnectionPool.java: borrow | awaitNanos 返回值忽略 | 返回值可用于剩余预算，但此处每次从 System.nanoTime 截止时间重新计算；循环重查条件处理虚假唤醒。不是永久等待或超时失效证据 | 保留，未修改 |
| ConnectionPool.java: objectMethod；FakeJdbc.java / MysqlFixtureSafetyTest.java | switch completeness | 当前 switch 有 default，测试替身对未知操作明确抛错；未发现缺失导致的编译或控制流错误。没有具体其他行号，不套用到所有 switch | 保留，未修改 |
| DatabaseConfig.java: load() | 未使用方法 | 当前尚无应用启动入口，无参配置入口预留用于后续组装；删除会破坏已批准基础 API。带参数入口被配置测试使用 | 保留，未修改 |

### IDEA_FALSE_POSITIVE：4 类上下文或有意行为

第一类：未解析表。以下每行均为 **B：IDE 上下文问题**，不是实际 MySQL 表缺失或错误 SQL；处理统一为绑定 Java 源目录的 SQL 解析作用域，**不修改 Java/SQL**。

| 文件 | Inspection 内容 / 表 |
| --- | --- |
| JdbcTestStepDao.java | 无法解析 test_steps |
| JdbcProjectCounterDao.java | 无法解析 project_counters |
| JdbcTestPlanCaseDao.java | 无法解析 test_plan_cases（及查询连接到的 test_cases） |
| JdbcProjectMemberDao.java | 无法解析 project_members |
| JdbcTestCaseRequirementDao.java | 无法解析 test_case_requirements |
| JdbcUserDao.java | 无法解析 users |
| JdbcProjectDao.java | 无法解析 projects |
| JdbcTestPlanDao.java | 无法解析 test_plans |
| JdbcTestCaseDao.java | 无法解析 test_cases |
| JdbcRequirementDao.java | 无法解析 requirements |
| UserDaoIntegrationTest.java | 无法解析 users |
| MysqlFixture.java | 无法解析清理/初始化涉及的表 |

实际本地配置证据：SqlDialectMappings 仅指定 schema.sql / seed.sql 为 MySQL；SqlResolveMappings 仅映射 database 目录；DataSourcePerFileMappings 仅关联这两份 SQL。没有 src/main/java 或 src/test/java 的显式作用域映射。本报告不复制数据源 UUID、凭据或私有 XML。IDE 数据库面板已有表，并不等于 Java 字符串具有相同解析作用域。

SQL 已被识别并产生表名告警，说明缺少的主要是表结构上下文。`@Language("SQL")` / `//language=SQL` 标记语言，不能替代数据源绑定，因此未增加注解依赖，也未在 DAO 中批量添加 SqlResolve suppression，更未写死 qatrack.users。

| 其余类别 | 文件 / Inspection | 是否真实问题 | 最小处理 / 是否修改 |
| --- | --- | --- | --- |
| 第二类 | MysqlFixture.java: resetRows，SqlWithoutWhere | 检测“全表删除”这一事实正确，但在已经核验的独立测试库里是有意清理，不是开发数据被删除的证据 | 仅在 10 条固定 DELETE 调用前各加 `//noinspection SqlWithoutWhere`；没有增加 WHERE、LIMIT 或改变 SQL |
| 第三类 | MysqlFixture.java: cleanup，SqlSourceToSinkFlow | DROP 的 schema/table 标识符经过测试 schema、实际 catalog、成功创建清单及格式检查，未接收用户 SQL；数据流分析难以推断完整约束 | 仅在这一条 DROP 调用前加 `//noinspection SqlSourceToSinkFlow` 和解释；未扩大到方法/类/项目 |
| 第三类的其他可能位置 | MysqlFixture.java: installSchema / count，“SQL 字符串可能不安全” | installSchema 当前仅接收冻结仓库脚本及安全测试常量；count 表名只允许 users/projects。属于受控来源；PreparedStatement 也不能用 ? 参数化表名 | 保留告警供局部复核，不增加 suppression；若未来输入来源变化必须重新审计 |
| 第四类 | PHASE2-JDBC-FOUNDATION.md，“应为 class/interface”等 | Markdown 正确；方法体中的 try-with-resources 片段不是完整编译单元，被按 .java 顶层解析产生错误 | 原文及 fence 未改；给出本地 Markdown 注入设置 |

SqlWithoutWhere 是通用风险提醒，SqlSourceToSinkFlow 是保守数据流分析；这里归入 IDEA_FALSE_POSITIVE 指“不能当作当前实际缺陷”，不表示检查器本身有 bug。局部 suppression ID 和语法依据 [JetBrains SqlWithoutWhere](https://www.jetbrains.com/help/inspectopedia/SqlWithoutWhere.html) 与 [SqlSourceToSinkFlow](https://www.jetbrains.com/help/inspectopedia/SqlSourceToSinkFlow.html)。

## 2. Markdown 实际核验

三个 fenced block 完整闭合：第 10–25 行 text、第 75–86 行 java、第 119–127 行 powershell；language tag 与内容匹配。未发现 fence 未闭合或误标语言。

额外将 java 围栏中的原始片段放进临时方法体，补齐导入和 SQLException 声明，使用 Java 21 javac 对现有编译类验证，编译成功。没有执行片段或调用无参 DatabaseConfig.load，因此没有借此连接开发库。临时编译文件放在仓库外，不新增产品 Java。

## 3. 精确 IDEA 本地设置

本轮只读取相关本地映射文件，**未修改任何 .idea 配置、未操作现有数据源连接**。下列由用户在 IDEA 设置，不加入 Git。

### 推荐：以冻结 DDL 提供离线解析上下文

1. `View → Tool Windows → Database` 打开数据库面板。
2. 打开 `Data Sources and Drivers`（面板工具栏 Data Sources，或选中数据源后 Shift+Enter）。
3. 左上 `+ → DDL Data Source`，命名为 `QATrack V1 DDL`。
4. `Sources → +`，仅选择 `database/schema.sql`；Dialect 选择 **MySQL**，Apply / OK。确认其虚拟结构里出现 19 张表，不添加 seed/constraint-tests 作为 DDL 来源，也不要执行 schema.sql。
5. `Ctrl+Alt+S → Languages & Frameworks → SQL Resolution Scopes`，添加 `src/main/java` 和 `src/test/java`，两行的 Resolution Scope 都选这个 DDL 数据源中包含上述表的默认 schema / 结构。检查没有更具体的旧映射覆盖。
6. 同一 Settings 下 `Languages & Frameworks → SQL Dialects`，将这两个目录（或项目默认）设为 **MySQL**。
7. Apply / OK，返回 Java SQL 字符串检查解析。IDE 索引完成后重新执行提交前 Analyze code。

DDL Data Source 只从文件建立虚拟表结构，测试实例关闭或测试表被清理也不影响解析；不会改变运行时 JDBC URL。[JetBrains DDL 数据源](https://www.jetbrains.com/help/idea/ddl-data-sources.html)、[SQL Resolution Scopes](https://www.jetbrains.com/help/idea/settings-languages-sql-resolution-scopes.html)、[SQL Dialects](https://www.jetbrains.com/help/idea/settings-languages-sql-dialects.html)。

如果使用现有 @localhost 作为编辑器上下文：`Database → @localhost → Properties / Data Sources and Drivers → Schemas` 确认选择 qatrack，并在 SQL Resolution Scopes 中将两个 Java 源目录指向 **@localhost / qatrack**。这只是可选本地设置说明，本轮没有执行连接/同步开发库，也不建议从 Java fixture 编辑器向开发数据源执行任何清理 SQL。离线 DDL 方案更适合当前测试表会被删除的流程。

### Markdown

`Ctrl+Alt+S → Languages & Frameworks → Markdown → Code fences`：取消 **Show problems in code fences**。可先保留 **Inject languages in code fences** 以保留辅助；若本地版本仍因注入显示片段语法错误，再取消该选项。只影响 Markdown 的代码片段辅助，不关闭 Java 源文件检查。不改技术内容或伪造完整示例类。[JetBrains Markdown 代码块设置](https://www.jetbrains.com/help/idea/markdown.html)。

## 4. 最终验证及变更范围

| 项目 | 最终结果 |
| --- | --- |
| mvn -B -ntp test | 39 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -B -ntp -Pmysql-tests test | 94 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -B -ntp -Pmysql-tests clean package | 94 tests，WAR BUILD SUCCESS |
| git diff --check | PASS；另检查暂存差异及新增报告空白 |
| MySQL | 独立 8.0.46 / 127.0.0.1:13307 / qatrack_test_r1；结束时表数 0，实例正常关闭、13307 无监听，原 MySQL80 Running |
| 开发库 | 未连接或修改 localhost:3306/qatrack |
| SQL / schema | 未改；Fixture 只有注释及 DROP 调用换行 |
| Markdown | 原 PHASE2-JDBC-FOUNDATION.md 完全未改；新增本审计报告 |
| ConnectionPool / TransactionManager / Maven | 全部未改 |
| 测试 | 未新增用例、未改变断言或测试逻辑；仅 Fixture 局部 suppression |

实际修改源文件仅 MysqlFixture.java；新增本报告与被忽略的审计验证产物。证据及 git status 原样输出在 [verification/idea-inspection-audit](verification/idea-inspection-audit/results.json) 同目录。已有暂存区保留，没有代用户 stage、commit 或 push；Fixture 本轮注释作为未暂存修改存在。

**提交判断：可以忽略本文已逐项确认的上下文告警及纯风格建议，不应整体关闭项目检查或盲目跳过新的 correctness 告警。** 本地作用域和 Markdown 设置尚需用户应用；本次并未验证 IDEA UI 中所有红线已经消失。没有完整报告的未知告警仍应按具体文件/行号判断。
