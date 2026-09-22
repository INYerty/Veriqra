# Phase 2 Round 1 — 针对性代码审查

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

审查日期：2026-09-07。范围为 ConnectionPool、JdbcTransactionManager、DatabaseConfig、JdbcUserDao、MysqlFixture，以及直接依赖的接口、Project DAO、Model、异常类、测试、pom.xml 和本地配置排除规则。结论依据本次实际文件和执行结果，不替代 Phase 1 冻结记录。

**修复后批准当前课程级连接池、显式共享 Connection 的事务模式，以及 User/Project DAO 的资源管理和映射模式作为后续基础。当前没有未修复的阻塞问题。** DAO 写入和写后回读应置于外层事务；不同领域对象的更新规则不能机械复制。

## 1. 按严重程度列出的发现

以下严重程度描述修复前的影响；本节问题均已在本轮修复。

### CRITICAL

none。未发现当前 fixture 会直接连接或清理开发库 qatrack 的路径；本轮未连接开发库。

### HIGH

1. **缓存 PreparedStatement 会让已关闭的逻辑句柄重新可用。** `ConnectionPool.wrapChild` 原先只依赖底层对象的关闭状态。开启 Connector/J 服务端预编译及缓存后，驱动复用物理 Statement，旧代理的 `isClosed()` 重新返回 false，旧引用还可能操作或关闭新借用者的 Statement。已在 MySQL 8.0.46 / Connector/J 9.7.0 实际复现。现在每个子资源代理单独记录逻辑关闭状态，重复 close 无副作用，已关闭句柄拒绝执行。新增真实 MySQL 回归测试。
2. **本地配置的 WAR 排除不完整。** 原规则排除了 classpath resources 内的 `*.local.properties`，但放入 `src/main/webapp/WEB-INF` 的同名本地文件仍可能打包。Git ignore 不等于 WAR 排除，这是一条潜在凭据泄露路径；未发现实际泄露。pom 增加 `warSourceExcludes` 和 `packagingExcludes`。用两个无秘密的探针文件实际打包，确认归档、classes 和展开目录均未包含它们；检查后已删除探针。

### MEDIUM

1. **坏连接关闭期间提前释放容量，可能短暂突破 maxSize。** 旧 `discard()` 先减计数再关闭物理连接；慢关闭时另一线程可创建替代连接。以闩锁阻塞物理 close，在 maxSize=1 下复现。现在物理关闭完成后才释放名额，关闭操作在池锁外执行；新增确定性并发测试。
2. **只恢复 catalog，遗漏 schema 模式。** Connector/J URL 使用 `databaseTerm=SCHEMA` 时，catalog 方法不能恢复选中数据库。真实测试复现归还后仍选中 information_schema。现在同时捕获和恢复 catalog/schema；新增真实 MySQL 回归测试。该驱动模式见 [Connector/J connection properties](https://dev.mysql.com/doc/connectors/en/connector-j-connp-props-connection.html)。
3. **Fixture 部分安装失败后的清理范围、异常保留不够严格。** 原清理清单来自全部预期表名，而非已成功创建的表；单次 DROP 失败会中断其余清理，owner.close 还可能覆盖此前异常。现在只登记成功 CREATE，核对当前 schema 与所有权，以限定 schema 的表名清理，汇总后续异常到 suppressed；重复清理不重复使用旧引用。新增 5 项无数据库依赖的安全边界测试。
4. **提交成功后的连接清理失败，顶层异常无法区分“已提交”。** 原调用方容易将该异常理解成事务未提交并错误重试。现在成功 commit 后记录结果，若归还失败明确报告 `Transaction committed; connection cleanup failed ...`，同时保留 SQLException。新增提交后清理失败、业务异常加清理失败、回调意外 close 三项测试。并未添加自动重试。

### LOW

1. DatabaseConfig 公共构造器原接受不足 1 ms 的 Duration，与配置边界不一致；现统一下限为 1 ms、上限为 5 分钟。补充数值极值与显式空环境变量覆盖测试。
2. 已关闭的 Connection 代理调用 `isValid()` 原抛异常，现返回 false；负 timeout 仍抛 SQLException。补充现有测试断言。

## 2. 逐项审查结论

| 范围 | 结论及适用边界 |
| --- | --- |
| 并发 borrow / return、容量 | 容量预留、空闲队列和条目集合受同一锁保护；创建失败释放预留；本轮修复慢关闭期间的容量重叠。逻辑连接为一次独占租借，不跨线程共享。 |
| acquire timeout | 使用单调时钟截止时间，循环检查剩余时间和条件，支持虚假唤醒；中断恢复标志并转换 SQLException。超时限制容量等待，驱动创建、验证、关闭等 I/O 另有超时，不能当作整个调用的精确墙钟上限。 |
| pool.close 竞争 | 标记关闭并唤醒等待者，关闭已登记的物理连接；创建中的连接返回后发现池关闭也会关闭。归还已关闭池和 double close 不重新放入空闲队列。未发现当前路径上的确定性死锁或计数泄漏。 |
| Connection 代理 | equals/hashCode 使用代理身份；unwrap(Connection.class) 返回代理，拒绝取得物理/厂商连接；Statement.getConnection 和 ResultSet.getStatement 保持代理边界。未扩展厂商 API。 |
| Statement / ResultSet | DAO 使用 try-with-resources；连接归还时关闭遗漏 Statement，间接关闭其结果集；本轮阻止缓存 Statement 的旧逻辑句柄复活。元数据结果集仍应由调用者显式关闭。 |
| 连接状态恢复 | 未完成事务先 rollback，再恢复 readOnly、isolation、catalog、schema、autoCommit、warnings，以及项目约定的时区与 sql_mode；重置失败淘汰连接。不是任意 session SQL、临时表或全部 JDBC 可变状态的通用沙箱。 |
| 事务异常优先级 | 业务 SQLException 转换 DataAccessException；RuntimeException / Error 原样传播；rollback 和归还异常作为 suppressed 保留，不覆盖主异常。commit 异常不重试；commit 成功后清理异常明确已提交。 |
| Connection 所有权 | manager 借入并归还，多个 DAO 接收同一个显式 Connection。当前 DAO 不 close/commit/rollback Connection；回调误 close 会回滚并使后续 commit 失败，不能悄悄提交部分工作。回调主动 commit/setAutoCommit 等仍靠调用约定禁止。 |
| 配置优先级 | classpath defaults < external UTF-8 file < QATRACK_DB_* 环境变量，实际测试覆盖。显式空字符串不会回退：空密码可显式配置，空用户名/数字等拒绝；缺失密码拒绝。默认 localhost:3306/qatrack 只用于开发配置，fixture 不复用这些环境变量。 |
| 凭据 | 配置 toString 隐去 URL、用户、密码；数值校验不回显非法值。真实 local properties 被 Git 和 WAR 排除。原始 SQLException cause 仍保留诊断信息，后续日志/HTTP 层不得直接对外输出异常链或 Model 中的 passwordHash。 |
| User / Project DAO | 参数使用 PreparedStatement，SQL 标识符为代码常量；Statement/ResultSet 自动关闭；检查生成键和受影响行数；状态 enum、DATETIME(6)/LocalDateTime、Long/Integer 映射与冻结结构一致。 |
| null、乐观锁、返回对象 | nullable 数据正确绑定；必填字段交由数据库约束拒绝；未知 enum 转换失败。update 使用 id+lock_version，缺失/过期对象失败，不静默覆盖。insert/update 返回回读的新 record，不修改输入；返回值不代表外层已提交，回滚后不可当成持久化成功。 |
| DAO 边界 | 无 Service 业务逻辑、无硬删除；当前 User/Project 的不可变编号/创建者规则保留。使用 autoCommit=true 时写入与回读不是原子操作，后续写调用应统一进入 manager。快照、执行尝试等对象须按各自冻结规则设计 SQL。 |
| Fixture 防护 | 仅接受 loopback、显式有效端口、qatrack_test_*、无 URL 参数；拒绝 root 名称、非空 schema；核对实际 catalog 和 MySQL 8.0.46；持有命名锁防止合作测试并行使用同库。必须使用仅获测试 schema 权限的账户，用户名黑名单本身不能证明权限隔离。 |
| Fixture 失败与 CI | 部分 CREATE 失败仅清理已成功创建的表；清理失败会报告，残留对象使下次运行拒绝，而非自动清空。CI 需预建空的专用 schema/受限账户，并将 MySQL 服务映射到 loopback，使用 QATRACK_TEST_* 配置。未增加远程服务或自动建库功能。 |

## 3. 测试缺口与优先级

本轮新增 13 项测试，原 52 项变为 65 项；未改 User/Project DAO，也未为已有行为机械增加重复测试。

| 已补边界 | 新增数量 | 必须现在补的理由 |
| --- | ---: | --- |
| 缓存 Statement 旧句柄、schema 模式归还 | 2 | 在真实驱动上复现明确错误，直接影响后续 DAO 可靠性。 |
| 慢物理关闭与最大容量竞争 | 1 | 原并发计数存在可复现缺口。 |
| 事务提交/清理异常、误 close | 3 | 避免原始业务异常丢失和提交结果被误解。 |
| Fixture URL、非空库、部分创建、清理归属、异常优先级 | 5 | DDL 清理测试必须具备可独立验证的失败关闭边界。 |
| 配置极值、显式空值覆盖 | 2 | 保证公开配置入口语义一致。 |

建议以后补、当前不阻塞：真实网络在 commit 前后中断的故障注入（提交结果可能未知，不能自动重试）；两个独立连接同时更新同一版本的竞争测试（目前已有过期版本测试）；未来引入实际跨 DAO 业务事务时，再补对应完整回滚/死锁路径。当前无需构造人为虚假唤醒接口或增加生产级连接池机制。

## 4. 实际验证结果

使用 Java 21、IDEA 自带 Maven；只设置本次进程的 JAVA_HOME，未修改 Windows PATH。

| 命令 | 结果 |
| --- | --- |
| mvn -B -ntp test | 39 tests，0 failures / errors / skipped，BUILD SUCCESS |
| mvn -B -ntp -Pmysql-tests test | 65 tests，0 failures / errors / skipped，BUILD SUCCESS |
| mvn -B -ntp -Pmysql-tests clean package | 65 tests 全部通过，WAR BUILD SUCCESS |
| git diff --check | 通过；仅针对 Git 已跟踪差异，另检查本轮新增文本文件的空白。 |

真实测试使用 MySQL 8.0.46 / Connector/J 9.7.0，独立 `127.0.0.1:13307/qatrack_test_r1`。最后一次 package 后表数为 0，临时实例已通过 mysqladmin 正常关闭，进程和 13307 监听均消失，日志记录 Shutdown complete；原 MySQL80 服务仍 Running。未连接、清理或修改 localhost:3306/qatrack，也未读 IDEA 私有连接配置。

WAR 仅带 mysql-connector-j-9.7.0.jar；没有 JUnit、测试类、protobuf、.gitkeep 或 local properties。数据库验证 manifest 中 7 个冻结文件的 SHA-256 均一致。

证据：[机器可读结果](verification/phase2-r1-review/review-results.json)、[单元测试](verification/phase2-r1-review/maven-test.txt)、[MySQL 测试](verification/phase2-r1-review/maven-mysql-test.txt)、[完整打包](verification/phase2-r1-review/maven-clean-package.txt)、[真实驱动修复前失败](verification/phase2-r1-review/regression-before-fix.txt)、[容量竞争修复前失败](verification/phase2-r1-review/capacity-before-fix.txt)。

## 5. 是否允许后续继续

| 模式 | 决定 |
| --- | --- |
| ConnectionPool | 批准本轮修复后的课程级实现。driver.close 自身失败时只能报告/淘汰，不能证明服务端连接已经释放；关闭池应在停止接收新工作后进行。 |
| Transaction pattern | 批准显式共享 Connection；事务 callback 与 DAO 必须遵守连接所有权，不自行 commit、切换 autoCommit 或关闭连接。无嵌套事务或自动重试承诺。 |
| User/Project DAO 模板 | 批准资源管理、参数绑定、异常转换、类型映射和乐观锁模式；写入及回读置于外层事务。其他表按冻结领域约束选择可写字段和操作，不机械复制 CRUD。 |

必须修复后才能继续的问题：本轮列出的明确问题均已修复，无剩余阻塞项。

建议修复但不阻塞：未来统一日志脱敏和事务结果处理；在真正出现跨 DAO 业务用例时补对应集成测试；CI 明确配置独立 schema 和最小权限账户。Unsigned INT 的 lock_version 超过 Java Integer 上限会被明确拒绝，为既有类型约束，不在本轮修改数据库模型。

本轮修改 ConnectionPool、JdbcTransactionManager、DatabaseConfig、MysqlFixture、pom.xml，补充对应测试/测试假对象及说明、审查证据。没有新增 DAO、业务模块、Service、Servlet、前端；没有修改冻结 schema；没有 commit 或 push。

审查开始时 Round 1 的大部分 Java/config/docs 尚未跟踪，README.md 与 pom.xml 已有修改；docs/submission/ 亦为已有材料，本轮未改动。因此 `git diff --stat` 只显示跟踪文件差异，不能用来代表本轮全部修改。完整工作区情况应结合 `git status --short`。
