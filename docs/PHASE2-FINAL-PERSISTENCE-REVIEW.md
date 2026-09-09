# Phase 2 Final Persistence Review

审查日期：2026-09-08 至 2026-09-09。基线：V1 Domain Model Freeze v1.0，Java 21、原生 JDBC、MySQL 8.0.46、Connector/J 9.7.0。恢复工作时先检查实际 working tree 与 diff，继续已有审计，不覆盖 Round 4 未提交成果。

## 1. Final verdict

**批准 Phase 2 JDBC Persistence Foundation 完成。No blocking persistence issues found.**

批准范围是当前数据访问基础设施、19 表持久化接口和已验证的外层事务组合模式。完整业务合法性仍需遵守第 11 节约束；本次不实现业务层，也不承诺任意调用顺序都安全。

| 分类 | 文件 / 问题 | 阻塞与处理 |
| --- | --- | --- |
| CRITICAL | none | 无 |
| HIGH | none | 无 |
| MEDIUM | 课程第 5 页的日期格式化工具此前缺失 | 课程交付缺口，非 JDBC 数据错误；本次增加 DateTimeUtils 与 5 项测试，已关闭 |
| LOW | TestImport 构造阶段此前接受非 32 字节摘要 | 原 DAO 已阻止错误写入；本次补构造校验与边界测试，已关闭，无剩余阻塞 |
| INFORMATIONAL | 跨项目、历史引用后的 Mapping 改绑、权限、锁协议、数值上界、提交结果未知等 | 明确保留边界，见第 9、11、17 节；未把业务规则塞进 DAO |

实际重新核对 schema、Model/enum、19 个 DAO 接口及实现、连接池/事务/配置、集成测试和 Round 1～4 说明及审查报告。课程对照以实际《课程要求.pptx》第 5 页内容为准；Phase 1 文档中“尚未实现 DAO”等叙述是当时阶段记录，不作为当前状态。

## 2. 19-table coverage matrix

下表每行均实际存在 Model、同名 `XxxDao` 接口和 `JdbcXxxDao` 实现。三者分别位于 `model`、`dao`、`dao.jdbc` 包，没有遗漏或额外虚构的实体字段。

| Table | Model | 字段 | DAO contract / implementation | 主要能力 |
| --- | --- | ---: | --- | --- |
| users | User | 9 | UserDao / JdbcUserDao | ID/username 查询、insert、版本 update |
| projects | Project | 9 | ProjectDao / JdbcProjectDao | ID/key 查询、insert、版本 update |
| project_members | ProjectMember | 7 | ProjectMemberDao / JdbcProjectMemberDao | add、复合键查询、双向列表、existsRecord、版本 update |
| project_counters | ProjectCounter | 3 | ProjectCounterDao / JdbcProjectCounterDao | 初始化、复合键查询、项目列表、锁内 allocateNext |
| requirements | Requirement | 11 | RequirementDao / JdbcRequirementDao | ID/业务编号/项目查询、父锁、insert、版本 update |
| test_cases | TestCase | 12 | TestCaseDao / JdbcTestCaseDao | 当前定义查询、父锁、insert、版本 update |
| test_automation_identities | TestAutomationIdentity | 6 | TestAutomationIdentityDao / JdbcTestAutomationIdentityDao | ID/完整外部键/项目查询、父锁、insert |
| test_automation_mappings | TestAutomationMapping | 8 | TestAutomationMappingDao / JdbcTestAutomationMappingDao | add、身份/Case 查询、existsRecord、锁定读、版本 update |
| test_steps | TestStep | 4 | TestStepDao / JdbcTestStepDao | 复合键/排序查询、add、内容 update、当前步骤 remove/整组删除 |
| test_case_requirements | TestCaseRequirement | 8 | TestCaseRequirementDao / JdbcTestCaseRequirementDao | add、双向查询、existsRecord、复核状态更新、markRemoved |
| test_plans | TestPlan | 10 | TestPlanDao / JdbcTestPlanDao | ID/业务编号/项目查询、父锁、insert、版本 update |
| test_plan_cases | TestPlanCase | 4 | TestPlanCaseDao / JdbcTestPlanCaseDao | add、双向查询、exists、remove |
| test_runs | TestRun | 12 | TestRunDao / JdbcTestRunDao | 项目/Plan/ID 查询、父锁、insert、版本 update |
| test_run_cases | TestRunCase | 8 | TestRunCaseDao / JdbcTestRunCaseDao | insert、Run×Case/ID 查询、双向列表、父锁 |
| test_run_case_steps | TestRunCaseStep | 4 | TestRunCaseStepDao / JdbcTestRunCaseStepDao | insert、复合键/排序查询 |
| test_imports | TestImport | 8 | TestImportDao / JdbcTestImportDao | insert、ID/requestKey 查询、Run 列表 |
| test_attempts | TestAttempt | 13 | TestAttemptDao / JdbcTestAttemptDao | insert、ID/token 查询、历史列表、普通/锁定 latest |
| defects | Defect | 14 | DefectDao / JdbcDefectDao | ID/业务编号/项目查询、insert、版本 update |
| test_attempt_defects | TestAttemptDefect | 4 | TestAttemptDefectDao / JdbcTestAttemptDefectDao | add、双向关系查询、existsRecord、错误链接 remove |
| 合计 | 19 Model | 154 | 19 interfaces + 19 implementations | 覆盖冻结表 |

## 3. 154-field mapping conclusion

逐列比较 schema 列名与 record component，全部 154 字段对应，无遗漏、无虚构字段。生成 ID、数据库默认时间和初始版本仍有 Model/读取映射，只是不从 insert 输入写入；关联表的复合主键没有制造虚假 ID。

| SQL 类型 | Java 映射 | 结论 |
| --- | --- | --- |
| BIGINT | Long | ID、FK、业务编号、Counter；nullable FK 不变为 0 |
| INT UNSIGNED | Integer | lockVersion/attemptNo 明确限制 Java signed 上界；读取越界拒绝，不截断为负数 |
| SMALLINT UNSIGNED | Integer | 两类 stepOrder 可容纳 1..65535，未使用 Short |
| BIGINT UNSIGNED | Long | durationMs 支持 NULL/0..Long.MAX_VALUE；超出读取由驱动报 22003，已有实测 |
| DATETIME(6) | LocalDateTime | getObject 类型化读取、TIMESTAMP 类型绑定；保留微秒，不经过文本工具 |
| VARCHAR/TEXT | String 或 enum | 状态/角色按冻结 CHECK，普通文本原样参数化 |
| BINARY(16) | UUID | 两个大端 long，16 字节；无 swap |
| BINARY(32) | byte[] | 非空值必须 32 字节；构造/访问复制；DAO 读取宽度检查 |

schema 有 23 个 nullable 字段，均使用引用类型且 JDBC mapper 保留 NULL。其余字段的 NOT NULL 由数据库执行；Java record 并不全面强制非空，允许构造尚未保存、缺少生成值的输入对象。TestImport 的 null 摘要同样仍作为缺失输入交由 NOT NULL 拒绝；非空但长度不合法的值在构造时即拒绝，不能被 BINARY 自动补零。该约定不同于“所有 Model 构造器都校验数据库完整性”。

LocalDateTime 不携带时区；连接池初始化/归还恢复 UTC 会话约定。日期工具只提供文本显示/解析，不更改 JDBC 精度或 schema。

## 4. enum / CHECK audit

17 个 enum、20 个枚举列的允许集合与 CHECK 一致。Priority 被多个表复用，没有数据库允许但 Java 无法映射的当前枚举值。

| Enum | 值 |
| --- | --- |
| SystemRole | ADMIN, USER |
| UserStatus | ACTIVE, DISABLED |
| ProjectStatus | ACTIVE, ARCHIVED |
| ProjectRole | TESTER, DEVELOPER |
| MembershipStatus | ACTIVE, INACTIVE |
| CounterEntityType | REQ, TC, PLAN, BUG |
| Priority | LOW, MEDIUM, HIGH |
| RequirementStatus | DRAFT, ACTIVE, ARCHIVED |
| TestCaseStatus | DRAFT, READY, ARCHIVED |
| TraceabilityStatus | CONFIRMED, NEEDS_REVIEW, REMOVED |
| TestPlanStatus | DRAFT, READY, ARCHIVED |
| TestRunStatus | IN_PROGRESS, COMPLETED, CANCELLED |
| TestAttemptStatus | PASS, FAIL, BLOCKED, SKIPPED |
| DefectStatus | OPEN, IN_PROGRESS, RESOLVED, CLOSED, REOPENED |
| DefectSeverity | LOW, MEDIUM, HIGH, CRITICAL |
| AutomationSource | JUNIT |
| AutomationMappingStatus | ACTIVE, INACTIVE |

枚举使用 name/valueOf；未知值转换为 SQLException 22000，再由 DAO 包装 DataAccessException。JdbcValues 负责数值范围而非枚举解析。NOT_RUN 表示没有 Attempt，不是额外枚举；SKIPPED 与 BLOCKED 分开。Import 不虚构 source/status 列。

## 5. DAO consistency / SQL

单主键使用 findById；复合主键使用 find(两端键)；业务键方法接受实际唯一键全部组成部分。单条查询返回 Optional，列表返回不可修改 List，方向与关系记录返回类型一致。成员、追溯、Mapping 的 existsRecord 包含 INACTIVE/REMOVED；不表示业务有效。TestPlanCase.exists 保留既有审查结论，因为该表只有物理增删，没有另外的停用状态。

19 个实现中 SQL 值均经 PreparedStatement，固定片段拼接不含外部输入；无 SELECT *。逐个核对 INSERT 列与参数序号、UPDATE 可变字段与绑定序号、mapper 列标签及类型，未发现错位。生成键使用独立 try-with-resources，检查存在后回读整行。写后返回新 record，不改变输入，返回对象不是提交凭证。

列表 ORDER BY 包含在其查询范围内唯一的序号/对端键，或时间加 ID；latest 按 attempt_no 降序 LIMIT 1。PlanCase JOIN 通过 Case 主键，是多对一，不放大关系行。历史查询读取快照表，不 JOIN 当前定义替换历史内容。

nullable 参数通过带 Types 的 setObject、setString 或 setBytes 写 SQL NULL；不要求机械改写为 setNull。没有 JDBC NULL 被误转为业务默认值的问题。SQL 异常保留 cause、SQLState 和 vendorCode。无版本关系的 boolean update 仅表示 JDBC 更新计数大于零；useAffectedRows 差异下同值更新可能为 false，不等于版本冲突或无权限。

## 6. Transaction ownership

Connection 由调用方或 JdbcTransactionManager 借入并归还；DAO 只持有同一连接，不借独立连接、不 commit/rollback/close/setAutoCommit，不自动重试。Statement/ResultSet 由 DAO 的 try-with-resources 关闭。DAO 与一次连接租借同生命周期，不跨线程共享。

manager 对 SQLException/RuntimeException/Error 回滚；rollback 和 close 失败作为 suppressed 保留，不能替换原始失败。SQLException 统一转换，RuntimeException/Error 原样传播。commit 成功后的清理失败明确标记已提交；commit 报错可能结果未知，不自动 retry。回调不得自行提交、改变 autoCommit 或吞掉整单应回滚的异常。

| 组合 | 真实测试证据 |
| --- | --- |
| Counter + 资产 | ProjectCounterDaoIntegrationTest：24 次分配与需求写入对应；后续 UNIQUE 失败恢复 Counter/资产 |
| BUG Counter + Defect + Link | ExecutionTransactionIntegrationTest：成功一起提交，Link FK 失败一起回滚 |
| Run + 多项快照/步骤 | 第二个快照完成后插入重复步骤失败；新事务确认 Run、两条快照、所有步骤均不存在，原 Case 保留 |
| Attempt 追加 | 重复序号导致当前事务新增尝试回滚；FAIL/PASS 保留两行 |
| Import + Mapping/Identity + Attempt | 第二个 Attempt 触发批次×RunCase UNIQUE；批次、映射、身份及先前 Attempt 全回滚，已有 Run 保留 |

以上证明当前 DAO 可以组合原子操作，不表示完整业务验证已实现；不同资产类型复用相同事务模式，没有为每种组合堆叠重复测试。

## 7. Optimistic locking

9 张版本表：users、projects、project_members、requirements、test_cases、test_plans、test_runs、defects、test_automation_mappings。全部使用 JdbcValues.version/writableVersion，初始版本由 schema DEFAULT 0 提供。UPDATE 同时递增版本与 updated_at，以主键及旧版本限定；Member 使用 project_id + user_id + version。影响行数不为 1 抛 OptimisticLockException，无静默覆盖。

各表均有实际过期版本测试，不只测 User。项目/业务编号、创建者等不可变身份不参与 UPDATE；Mapping 允许写 Case/status，但历史引用后的可否改绑仍归业务校验。没有版本列的关系/步骤不伪造版本，由父锁和外层版本协调。

## 8. Append-only / snapshot integrity

TestRunCase、TestRunCaseStep、TestAttempt、TestImport、TestAutomationIdentity 接口和 SQL 均无普通 update/delete。Case 和当前 Step 编辑后的历史独立性已有真实测试；Run×Case、step_order 复合主键及 attempt_no 唯一约束保持冻结设计。

FAIL 后 PASS 是两条事实；最新状态按尝试序号推导。Identity 的 project/source/namespace/externalKey 不改写。Mapping 停用保留行，允许未使用时纠正 Case；数据库不能阻止已使用 Mapping 的 Case 被直接更新，不能把 append-only Attempt 误当作整条引用链天然不可变。

AttemptDefect.remove 只纠正错误关联，不删 Attempt/Defect，也不因为 PASS 自动清理失败证据。直接 SQL 仍可能绕过 DAO 的历史保护，RESTRICT 不是不可变触发器。

## 9. Lock / concurrency audit

当前 9 处 FOR UPDATE：

| DAO / 方法 | 锁对象 |
| --- | --- |
| JdbcProjectCounterDao.allocateNext | 指定项目×类型 Counter |
| JdbcRequirementDao.findByIdForUpdate | 指定 Requirement |
| JdbcTestCaseDao.findByIdForUpdate | 指定 Case |
| JdbcTestPlanDao.findByIdForUpdate | 指定 Plan |
| JdbcTestRunDao.findByIdForUpdate | 指定 Run |
| JdbcTestRunCaseDao.findByIdForUpdate | 指定 RunCase |
| JdbcTestAttemptDao.findLatestByRunCaseForUpdate | 指定 RunCase 的最新 Attempt 当前读 |
| JdbcTestAutomationIdentityDao.findByIdForUpdate | 指定 Identity |
| JdbcTestAutomationMappingDao.findByIdentityForUpdate | 指定 Identity 的 Mapping 当前读 |

这些入口要求外层事务。没有全表 FOR UPDATE、无锁 SELECT MAX + 1、自动重试或 DAO 内隐藏的跨对象逆序锁。Attempt 并发测试按 Run → RunCase → latest Attempt，取当前序号后 addExact + 1；空 Attempt 集合也必须锁稳定父 RunCase。普通 latest 在 REPEATABLE READ 下可返回旧视图，锁定 latest 才是当前读，测试明确区分。

冻结全局顺序仍为 Project → User/Membership → Counter → Requirement → Case → Identity/Mapping → Plan → Run → RunCase → Defect → links，同类按 ID 排序。Mapping 缺行时先锁 Identity；多集合先取 ID 再锁后重验。DAO 不自动执行完整协议；未来完整业务事务可能需要针对性的 Project 共享/独占锁、成员或 Defect 锁定查询，目前没有这些 API，不能用普通 findById 假充锁定读。本轮不提前扩展。

Counter 4 线程 24 次、Attempt 4 线程 16 次测试证明指定调用协议下结果一致；未协调的重复序号由 UNIQUE 拒绝。不据此宣布全系统无死锁，后续完整流程需验证归档/完成/编辑/追加等竞争。

## 10. ProjectCounter

REQ/TC/PLAN/BUG 都映射冻结 CounterEntityType。项目创建者需显式初始化四行；DAO 无懒创建、无任意重置或编号字符串格式化。

allocateNext 在同一连接锁定既有 Counter，读取旧 next_value，UPDATE +1 后返回旧值，锁保持到外层结束。缺行 02000、无事务 25000、无法递增的上界 22003；不使用 MAX、LAST_INSERT_ID 会话技巧。分配与实体写入必须在同一事务；失败可恢复，已提交编号不回收。Long.MAX_VALUE 作为 next_value 时拒绝继续分配，因为无法保存下一值。

## 11. Cross-project Service invariants

全部 40 FK 只引用父 PK，DELETE/UPDATE RESTRICT，保护存在性及被引用键；不是跨链项目一致性约束。隔离库中的错误关系探针主动回滚，不污染正常测试数据。

| Invariant | Database guarantees? | Service required? | Risk if omitted? |
| --- | --- | --- | --- |
| Requirement ↔ Case 同项目 | 否；FK 两端存在、复合 PK、复核形状 CHECK | 是，锁父记录后比较项目/复核内容 | 跨项目覆盖和追溯错误 |
| Plan ↔ Case 同项目 | 否；FK/关系 PK | 是，锁定 Plan/Case 并校验范围 | 计划包含其他项目用例 |
| Run ↔ 可选 Plan 同项目 | 否；Run.project_id 非空 FK、Plan 可空 FK | 是；Ad-hoc 不强制 Plan | 错误项目归属与来源 |
| RunCase ↔ Case 同项目、快照来自正确版本 | 否；FK、Run×Case UNIQUE | 是，锁当前定义并原子复制头/步骤 | 跨项目执行或混合版本快照 |
| Attempt ↔ Defect 同项目且 Attempt 为 FAIL | 否；FK/复合 PK | 是；实测 PASS/跨项目可写入 | 伪缺陷证据 |
| Identity ↔ Mapping.Case 同项目 | 否；FK，Identity 四元键 UNIQUE，Mapping.identity UNIQUE | 是；比较项目、ACTIVE 与历史引用 | 自动化结果归入错误用例 |
| 已引用 Mapping 不可改绑 Case | 否；FK 只保护 Mapping ID | 是，Identity/Mapping 锁内核对引用 | 历史 Attempt 含义被改写 |
| Import.Run = Attempt.RunCase.Run | 否；各 FK 独立；批次×RunCase UNIQUE | 是，批量校验目标 Run | 报告归属和结果不一致 |
| Attempt.Mapping.Case = RunCase.Case | 否；各 FK 独立 | 是；锁内核对目标 | 用例结果错配 |
| Identity namespace/source 与 Import 一致且 Mapping ACTIVE | 部分；source CHECK 仅 JUNIT，状态 CHECK 仅枚举 | 是；实测 namespace 不同/INACTIVE 可写入 | 错误模块或停用实现进入结果 |
| 每 RunCase 自动化使用同一个 Mapping | 否；无该跨行约束 | 是，父执行项锁内校验 | 多实现碰撞改变最新结果含义 |
| 用户/成员/负责人、项目状态、操作权限 | 部分；FK 与枚举形状 | 是，锁协议与角色校验 | 越权或失效负责人 |
| 非空 Run/批次/步骤，步骤连续、父终态只读 | 部分；单行正数/状态形状 CHECK | 是，跨行/状态机校验 | 空壳、断序、终态新增结果 |
| 同请求/提交 token 的业务载荷一致 | 部分；两个全局 UNIQUE 防重复行 | 是，比较目标/权限/摘要等，不吞唯一异常 | 重复请求被错误当作成功 |

数据库已有可表达约束未被移除，也未为了补业务边界修改 schema。

## 12. Soft-delete / status semantics

| 对象 | 持久化语义 |
| --- | --- |
| ProjectMember | INACTIVE 保留记录与 joinedAt；恢复复用原行；existsRecord 始终按物理行 |
| TestCaseRequirement | REMOVED 保留首次链接元数据并清空复核字段；恢复显式写复核状态；双向查询包含 REMOVED |
| TestAutomationMapping | INACTIVE 保留身份绑定记录及历史引用；无 remove |
| TestPlanCase | 当前范围关联允许物理 remove，不影响历史 Run |
| TestAttemptDefect | 仅错误链接纠正可 remove，两端证据保留 |
| TestStep | 当前步骤可删改/整组替换；父版本和全部步骤必须同事务 |

仅 TestStep、TestPlanCase、TestAttemptDefect 实现物理 DELETE。其余归档/停用通过冻结状态字段，没有强行实现每表 hard delete。

## 13. UUID / hash / date utility

submissionKey/requestKey 用 UUID，ByteBuffer 默认大端，先高 64 位再低 64 位，编码/解码互逆且固定 16 字节。实际 BIN_TO_UUID(...,0) 往返通过。NULL 查询不匹配；NULL 写入由 NOT NULL 拒绝。两个 token 各自全局 UNIQUE，DAO 查询与约束一致。

TestImport 的非空 reportSha256 在构造阶段验证恰好 32 字节，构造和 getter 都 clone，equals/hashCode 使用二进制内容。原 DAO 防御检查保留，无行为冲突；读取再次确认宽度。32 字节接受及防御复制、0/31/33 字节拒绝均有单元证据。保留一次修复前失败日志，证明新增校验测试确实能发现原缺口。集成测试继续验证 NULL 拒绝、32 字节原值、重复令牌不同载荷拒绝、同摘要不同令牌允许。

[DateTimeUtils](../src/main/java/io/github/lz007001cn/qatrack/util/DateTimeUtils.java) 提供 LocalDate / LocalDateTime 各自 format/parse，格式为年-月-日和年-月-日 时:分:秒。使用 static final DateTimeFormatter、Locale.ROOT、ResolverStyle.STRICT；模式用 uuuu 表示公历年份，避免严格解析 yyyy 时缺少 era 的问题。没有可变共享格式器、第三方依赖、时区转换或 SimpleDateFormat。非法文本的 DateTimeParseException 直接传播；非空是调用前提。

日期时间文本只到秒，format 不输出小数秒，parse 得到零 nanos；明确不适合承担 DATETIME(6) 的无损序列化。现有 DAO 仍直接使用 LocalDateTime，未改为调用此工具。5 项 DateTimeUtilsTest 覆盖日期格式化/闰日解析、日期时间格式化/解析、无效日历值/时分秒/格式拒绝。

## 14. Fixture safety

保留现有 MysqlFixture，没有继续重构或扩大 suppression。只接受 localhost/127.0.0.1 + 显式端口 + qatrack_test_*，拒绝 URL 参数和 root 名称；开发 schema qatrack 在连接前被拒绝。测试配置与应用配置独立，本次实际仅使用 127.0.0.1:13307/qatrack_test_r1，专用测试账号。

开始检查实际 catalog、精确 MySQL 版本、命名锁及空 schema。仅登记成功创建的表，部分 setup 失败只清理已拥有对象；DROP 重新检查 catalog/标识符，限定 schema 名，汇总异常并关闭 owner。非空未知库拒绝，失败不会自动清空未知表。非 root 检查不是完整权限证明，部署测试环境仍须维持账号仅限测试 schema。

固定 DELETE 顺序覆盖 19 表：attempt_defects → attempts → imports → automation_mappings → automation_identities → defects → run_case_steps → run_cases → runs → plan_cases → case_requirements → steps → plans → cases → requirements → counters → members → projects → users（省略各表共同前缀）。结构检查逐条验证 40 个 FK 均先子后父，真实测试执行通过；不关闭 FOREIGN_KEY_CHECKS，不加载 seed。SqlWithoutWhere 仅每条已审查清理 SQL，动态 DROP suppression 只在校验后的语句。

最终表数、实例关闭证据见第 18 节。端口限制是本次运行选择；Fixture 本身也允许其他端口上的合规独立 test schema，不声称代码固定禁止 3306 端口。

## 15. Test coverage

原 137 项基线增加 1 项 SHA-256 构造边界测试、5 项日期工具测试，共 143 项；默认非 MySQL 测试 47 项，真实 MySQL 测试 96 项。原摘要集成测试把非法长度断言移至 Model 单元测试，保留 NULL 拒绝并验证没有新增行，没有删除对应风险覆盖。

9 个版本实体均有过期更新拒绝证据；nullable Plan/assignee/执行来源/复核字段、复合主键与 UNIQUE、FK/CHECK、双向查询、快照独立、UUID 字节序、无符号数值上界、重排/多快照/多 Attempt 回滚已有测试。append-only 还结合接口/SQL 静态审查，未为了接口不含某方法而堆砌反射测试。

目前没有需要阻塞持久化层完成的高风险未测分支。以后有实际调用流程后再验证多实体状态竞争、幂等载荷冲突及完整锁顺序；真实 commit 网络中断的结果未知需要专门故障注入。143 不是业务覆盖率、压力指标或无死锁证明。

## 16. Course Phase 2 requirement mapping

对照实际课程 PPT 第 5 页“阶段二：后端基础开发”。

| Requirement | Implemented? | Evidence | Remaining? |
| --- | --- | --- | --- |
| 数据库表对应 Java 实体 POJO | 是 | 19 个无框架依赖的 immutable record，154 字段 | PPT 未要求无参构造/getter-setter JavaBean；答辩说明使用 Java 21 record |
| DAO 数据访问接口 | 是 | 19 个 XxxDao | 无 |
| JDBC 查询/插入/更新/删除 | 是，按领域允许操作 | 参数化查询/写入；当前步骤、计划关系、错误缺陷链接可删除，历史只追加 | 不为字面 CRUD 给历史表增加危险删除 |
| 自行编写连接池 | 是 | ConnectionPool，真实驱动与并发/关闭测试 | 保持课程级能力边界 |
| SQL 异常处理类 | 是 | DataAccessException / OptimisticLockException，状态码/原因保留 | 无持久化交付缺口 |
| 日期/时间映射 | 是 | LocalDateTime、微秒往返、UTC 会话约定 | 与文本格式化分别说明 |
| 日期格式化工具 | **是** | DateTimeUtils + DateTimeUtilsTest 5 项通过 | 原缺口本次关闭 |
| 统一包规范、JavaDoc | 是 | model、dao、dao.jdbc、jdbc、config、exception、util；类/接口契约及高风险方法注释 | 不追求所有机械访问器重复注释 |
| 连接池可参数化 | 是 | URL、账号、密码、initial/max/acquireTimeout；文件/环境优先级及边界测试 | 本地凭据仍外置 |
| 接口/实现分离 | 是 | DAO interface 与 JdbcXxxDao 独立文件/包 | 无 |
| 核心表 DAO 与架构说明文档 | 是 | 全 19 表；Round 1～4 说明及本报告 | 无代码/文档内容缺口 |
| 每周提交代码增量 | 工作区内容具备，提交节奏不能仅由代码证明 | Git 历史 + 本轮未提交 Round 4 成果 | 本次按要求不 commit/push；由用户完成提交，不能声称已经递交 |

课程 Phase 2 技术与文档要求均已满足；“每周递交”的行政完成情况和教师最终验收不由本次构建结果代替。

## 17. Remaining risks

- 无剩余 CRITICAL/HIGH/MEDIUM/LOW 待修实现问题。表间业务合法性和第 11 节规则仍需未来调用方保证，DAO 成功不等于业务合法。
- INT/BIGINT UNSIGNED 全范围大于选定 Java signed 类型；当前对越界明确失败，没有无损覆盖数据库全部合法数值的承诺。
- autoCommit=true 下多次 DAO 写入无法整体回滚；事务连接与 DAO 不得跨线程/租借复用。未来完整工作流的部分锁定 API 仍需按实际用例补齐，不能绕过冻结协议。
- 列表未分页；连接池不是任意 session 改造的隔离沙箱，close/网络失败也不能保证服务端动作结果；不能盲目重试未知提交结果。
- 原始 SQLException cause 可含数据库值；不得直接对外打印异常链或敏感 Model 字段。本地配置必须继续保持 Git/WAR 排除。
- 日期工具到秒的格式不是数据库微秒序列化格式；没有修改既有时间映射。

## 18. Final test results

本次使用 Java 21、IDEA 自带 Maven，仅为构建进程设置 JAVA_HOME，不修改系统 PATH。日志保存在已忽略的 `docs/verification/phase2-final-review/`，该目录不作为公开仓库提交内容。

| 命令 / 检查 | 本次结果 |
| --- | --- |
| mvn test | 47 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -Pmysql-tests test | 143 tests，0 failures/errors/skipped，BUILD SUCCESS |
| mvn -Pmysql-tests clean package | 143 tests，0 failures/errors/skipped，WAR BUILD SUCCESS |
| git diff --check | PASS；另检查全部 21 个未跟踪文本的 UTF-8、尾随空白及已知本机/身份信息 |
| WAR 内容 | 全 19 组 Model/DAO/JdbcDao 与 DateTimeUtils；仅 mysql-connector-j-9.7.0.jar；无 local properties、IDEA 配置、测试类 |
| MySQL 结束状态 | 8.0.46 / 13307；qatrack_test_r1 最终 0 表；mysqladmin 正常 shutdown，进程结束，13307 no listener |

没有连接或修改 localhost:3306/qatrack，没有修改 schema/seed、Maven 依赖、ConnectionPool、JdbcTransactionManager 或 DAO SQL；没有新业务模块。

## 19. Git status / change scope

恢复时已存在的 Round 4 文件及 MysqlFixture 六行修改保留。整个最终 Review 实际变更：TestImport.java、TestImportTest.java、TestImportDaoIntegrationTest.java、Round 4 说明；新增 DateTimeUtils.java、DateTimeUtilsTest.java、本报告。恢复后的新增实现仅为日期工具及其测试，上次摘要修复没有重复实现。

git diff --stat 只统计已跟踪差异，Round 4 与本报告等未跟踪文件不在其中。本轮未暂存任何文件。最终为 1 个 tracked modified + 21 个 untracked files（默认 status 折叠两个测试包目录）：

```text
 .../java/io/github/lz007001cn/qatrack/integration/MysqlFixture.java | 6 ++++++
 1 file changed, 6 insertions(+)
```

```text
 M src/test/java/io/github/lz007001cn/qatrack/integration/MysqlFixture.java
?? docs/PHASE2-FINAL-PERSISTENCE-REVIEW.md
?? docs/PHASE2-R4-AUTOMATION-IMPORT-PERSISTENCE.md
?? src/main/java/io/github/lz007001cn/qatrack/dao/TestAutomationIdentityDao.java
?? src/main/java/io/github/lz007001cn/qatrack/dao/TestAutomationMappingDao.java
?? src/main/java/io/github/lz007001cn/qatrack/dao/TestImportDao.java
?? src/main/java/io/github/lz007001cn/qatrack/dao/jdbc/JdbcTestAutomationIdentityDao.java
?? src/main/java/io/github/lz007001cn/qatrack/dao/jdbc/JdbcTestAutomationMappingDao.java
?? src/main/java/io/github/lz007001cn/qatrack/dao/jdbc/JdbcTestImportDao.java
?? src/main/java/io/github/lz007001cn/qatrack/model/AutomationMappingStatus.java
?? src/main/java/io/github/lz007001cn/qatrack/model/AutomationSource.java
?? src/main/java/io/github/lz007001cn/qatrack/model/TestAutomationIdentity.java
?? src/main/java/io/github/lz007001cn/qatrack/model/TestAutomationMapping.java
?? src/main/java/io/github/lz007001cn/qatrack/model/TestImport.java
?? src/main/java/io/github/lz007001cn/qatrack/util/DateTimeUtils.java
?? src/test/java/io/github/lz007001cn/qatrack/integration/ImportFixture.java
?? src/test/java/io/github/lz007001cn/qatrack/integration/ImportTransactionIntegrationTest.java
?? src/test/java/io/github/lz007001cn/qatrack/integration/TestAutomationIdentityDaoIntegrationTest.java
?? src/test/java/io/github/lz007001cn/qatrack/integration/TestAutomationMappingDaoIntegrationTest.java
?? src/test/java/io/github/lz007001cn/qatrack/integration/TestImportDaoIntegrationTest.java
?? src/test/java/io/github/lz007001cn/qatrack/model/
?? src/test/java/io/github/lz007001cn/qatrack/util/
```

Git 提示 MysqlFixture 下次操作时 LF 将转 CRLF，这是既有行尾配置提示，不是 diff --check 失败；未做行尾风格重写。数据库文件、pom 和基础设施与 HEAD 一致；审查开始时的内容哈希也确认只有上述三个既有摘要相关 Java 文件发生变更。完整原始日志及机器可读结果保留于本地忽略目录。

A. **批准 Phase 2 JDBC Persistence Foundation 完成。**

B. **无必须修复的剩余阻塞问题。** 两个本次发现已修复并有测试。

C. **建议用户 Review 本次差异后 commit/push Round 4 与最终审查成果。** 本次没有执行提交或推送。

D. **提交后可以进入 Service Layer。** 应以第 11 节业务边界及冻结锁协议作为后续验收依据；本次没有进入该阶段。

E. **课程 Phase 2 技术实现与架构文档要求已满足，包括日期格式化工具。** 实际递交/每周提交节奏不冒充已完成。
