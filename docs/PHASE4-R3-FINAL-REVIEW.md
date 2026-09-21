# Phase 4 Round 3 Final Review — Execution / Defect REST API

## 1. Final verdict

**APPROVED。** Phase 4 Round 3 的 TestRun、Attempt 与 Defect REST API 保持了既有 Handler → Service → DAO → JDBC 分层，关键 ownership、历史不可变、幂等、并发、状态机和权限规则仍由 Service 保证。审查未发现 commit 前必须修复的生产缺陷。

本次 Review 只补强两处已有测试证据：

- `ExecutionWebTest` 的未知字段检查加入 `projectRole`、`projectId`、`runId`、`runCaseId`，确认这些客户端伪造字段均在进入 Service 前返回 400。
- `ExecutionHttpIntegrationTest` 在 reopen 流程后直接读取 MySQL，确认状态为 `REOPENED`、BUG key 不变、resolution note 清空、旧 evidence 保留且新 FAIL evidence 已加入。

未修改生产代码、数据库 schema、`CLOUD-DEPLOYMENT-HANDOFF.md`、Fixture、ConnectionPool 或 TransactionManager。

## 2. Findings

### CRITICAL

none。

### HIGH

none。

### MEDIUM

none。

### LOW

1. Run、Defect 和 Attempt 列表尚无分页。当前课程数据规模可接受，不阻塞 Round 3；数据量扩大前应增加明确分页契约。
2. Run/Defect 详情采用多个简单 DAO 查询，存在 N+1 查询成本。所有读取仍在同一事务中，当前规模不需要引入 ORM、缓存或复杂聚合 SQL。
3. 返回 204 的状态 action 不返回新 version，调用方后续编辑前需要重新 GET。语义正确，不影响并发安全。

### INFORMATIONAL

1. 详情一致视图依赖 MySQL `REPEATABLE-READ`。本次真实 MySQL 8.0.46 验证值为 `REPEATABLE-READ`；这是部署运行前提，不在本轮修改全局隔离级别。
2. 登录限流、完整 CSRF token/严格 Origin 与 Host 校验、HTTPS 与 Secure Cookie、可信反向代理验证仍属于公网开放前的部署安全工作；它们不阻塞受控 smoke deployment。
3. 首次 `mvn test` 因命令漏传本机 Microsoft JDK 的已知 AF_UNIX workaround，在 Tomcat/HttpClient selector 初始化前失败。以 `-DargLine=-Djdk.net.unixdomain.tmpdir=<NONEXISTENT_SOCKET_DIR>` 重跑后 94/94 通过；该环境问题没有触发业务测试失败，也没有写入项目配置。

## 3. Architecture boundary

新增路由由 `ApiServlet` 分派至 `TestRunHandler` 或 `DefectHandler`，Handler 只完成路径/方法匹配、严格 JSON 解析、session actor 读取、DTO/command 映射和响应投影。代码搜索与逐方法审查确认 Web 层没有 DAO 调用、SQL、事务、attempt number 分配、幂等缓存、deadlock retry、状态机计算、evidence 表操作或角色规则。

所有业务写操作只调用一次对应 Service 入口。Service 使用既有 `ServiceTransaction` 与 DAO factory；DAO 仍只负责 JDBC persistence。

## 4. Run creation

`CreateRunRequest` 只允许以下两种互斥来源：

- `testPlanId`：调用 `createFromPlan`。
- `testCaseIds`：调用 `createAdHoc`。

两者同时存在或同时缺失均为 400。Project ownership、Plan/Case `READY` 前置条件和非空 case 集合由 Service 验证。Service 在一个事务内创建 `IN_PROGRESS` Run，并写入 TestRunCase 与 TestRunCaseStep 快照；Handler 没有回查当前 TestCase 来拼接快照。

## 5. Snapshot immutability

没有暴露 TestRunCase/TestRunCaseStep 的 update、delete 或同步入口。`runSnapshotsStayIndependentAndTerminalReplayStillWorks` 通过 HTTP 创建 Run，再修改原 TestCase/steps，随后比较完整 Run detail，确认历史 snapshot 未改变。

Run detail 返回 snapshot title、description、preconditions 与有序 steps；不会以当前 TestCase/TestStep 覆盖历史内容。

## 6. Nested ownership

所有 `/projects/{projectId}/runs/{runId}` 入口在 Service 内要求 `run.projectId == projectId`，ADMIN 也不能借错误 project path 访问资源。

Attempt 嵌套入口同时验证：

- Run 属于 URL 中的 project。
- RunCase 属于 URL 中的 Run。

集成测试覆盖了同一 Project 内把另一个 Run 的 RunCase 拼到当前 URL 的情况，读写和幂等 replay 均返回 400；也覆盖跨 Project Run、Defect 与 evidence Attempt 的组合。路径正确性独立于权限，ADMIN 不绕过该约束。

## 7. Attempt append-only

HTTP 仅提供 POST 创建 Attempt 与 GET 历史，没有 PUT/PATCH/delete 历史结果的入口。caller 不能提交 attempt id、attempt number、actor、project/run/runCase、created time 或 source；这些值来自数据库、URL、session 与 Service。

FAIL → PASS 会保留两条记录。真实 HTTP→MySQL 测试直接读取 DAO，确认 attempt number 为 `[1, 2]`，对应 FAIL/PASS id 均保留；历史 GET 按 attempt number 稳定返回。

attempt number 继续由 Service 锁定 Run、RunCase 后调用 DAO 的 latest-for-update 分配机制生成，不使用 `MAX + 1`，Handler 不参与计算。

## 8. submissionKey idempotency

同 key、同 payload 返回原逻辑 Attempt；同 key、不同 payload 返回 409。payload 比较仍覆盖 runCase、outcome、actor、import/mapping、duration、comment 与 failure message 等现有 Service contract 字段。

并发 HTTP 测试先建立两个任务，再通过同一 latch 同时放行，并非顺序等待 future。两个请求均获得合理的 201 响应和同一 Attempt 表示，最终历史只有一条记录；1062/1213 或 SQL 内容没有泄露到 HTTP。

Handler 没有幂等 cache 或 retry。唯一键竞争的恢复仍由 Service 在独立事务内读取既有 Attempt。

## 9. MySQL 1213 retry boundary

代码搜索确认 `1213`、deadlock 与 retry 逻辑没有扩散到 Handler、DAO 或其他 Service。受控行为仍局限于 `recordAttempt`：

- 只识别 MySQL 1213。
- 最多重新执行一次完整事务。
- 第 2 次 1213 继续抛出。
- 失败事务结束后不复用同一 Connection 继续查询。
- DAO 不 retry。

HTTP adapter 只调用一次 Service 方法。

## 10. Terminal Run idempotency

Service 先按 submission key 恢复已成功写入的相同 payload，再对新提交检查 Run 终态。因此 Run 已 `COMPLETED` 或 `CANCELLED` 后：

- 终态前成功提交的相同 key + 相同 payload 仍返回原 Attempt。
- 新 key 或相同 key + 不同 payload 返回 409。

HTTP 集成测试同时覆盖 completed 与 cancelled Run。Handler 没有提前判断 terminal 状态。

## 11. Complete / cancel

Run 终止使用显式 `POST /complete` 与 `POST /cancel` action，并传递 `expectedVersion`；没有 generic status PATCH。状态机、终态冲突和 optimistic locking 均在 Service。wrong-project path 返回 400，合法 action 返回 204，stale/非法状态返回 409。

## 12. Defect create

Defect 只能以 FAIL Attempt 创建。Service 从 Attempt 追溯 RunCase 与 Run 并核对 Project；PASS evidence 返回 409。BUG counter 分配、Defect insert 与首条 evidence link 在同一事务内执行。Handler 不访问 counter 或 relation DAO。

## 13. Defect lifecycle

Web 只暴露显式动作：start、resolve、close、reopen。没有 `PUT {"status":"CLOSED"}` 或 PATCH 状态入口。

Service 保持以下迁移：

- `OPEN → IN_PROGRESS`
- `IN_PROGRESS → RESOLVED`
- `RESOLVED → CLOSED`
- `RESOLVED/CLOSED → REOPENED`
- `REOPENED → IN_PROGRESS`

resolve 的 `resolutionNote` nonblank 规则由 Service 保证；Web 只保证 JSON 形状并传递 expectedVersion。stale version 与非法迁移均映射为 409。

## 14. Close/retest rule

close eligibility 完全位于 `DefaultDefectService`。Service 锁定并复核 evidence 集合，对每条 FAIL evidence 查找其 RunCase 的 latest Attempt；只有存在更高 attempt number 的 PASS 且当前 latest 仍为 PASS 才允许关闭。

真实集成测试覆盖：

- `FAIL #1 → PASS #2` 可满足。
- `FAIL #1 → PASS #2 → FAIL #3` 不能 close。
- 多条 evidence 的每个 RunCase 都必须满足，少一个 PASS 即拒绝。

Handler 不计算任何 close 条件。

## 15. Evidence

add evidence 只接受 FAIL Attempt，并校验同 Project。重复 add 是幂等操作。remove 只删除 Defect↔Attempt 关系，用于纠正错误链接；测试确认 Attempt 历史仍存在。CLOSED Defect 的 add/remove 均返回 409，Defect 与 Attempt 本身没有物理删除端点。

Evidence action 按已批准 Service contract 不接收 expectedVersion；Service 通过 Defect 行锁、状态复核和关系唯一约束处理并发，它不会让 caller 设置新 lock version。

## 16. Reopen

reopen 请求必须携带新的 FAIL evidence、assignee 与 expectedVersion。一个 Service transaction 内完成：

- 状态改为 `REOPENED`。
- 保留原 BUG key。
- 保留旧 evidence。
- 加入新 FAIL evidence。
- 清空 resolution note。

没有“先 reopen、再另一个 HTTP 请求添加 evidence”的分裂流程。补强后的测试除了读取 HTTP detail，还直接读取 MySQL 验证以上四项持久化结果；stale reopen 不增加 relation。

## 17. Permissions and live session identity

Handler 不判断角色。Service 继续执行实时项目成员查询：

- ADMIN、TESTER 具有当前业务范围内的完整能力。
- DEVELOPER 可 report/read，并可将 self-assigned defect 推进至 `IN_PROGRESS`/`RESOLVED`。
- DEVELOPER 不能 close、reopen 或管理 evidence。

Session identity 仍只保存 actor user id，不缓存 project role。集成测试把已有 Session 对应 membership 改为 `INACTIVE` 后，后续 Run read 与 Attempt write 均被 403 拒绝。

## 18. expectedVersion

Defect update、start、resolve、close、reopen 和 Run complete/cancel 都从专用 DTO 读取 expectedVersion。共享严格 Jackson 配置拒绝 missing、null、negative、fraction、string 与 integer overflow；合法但 stale 的值由 Service optimistic locking 映射为 409。响应中的 version 是服务端状态，request 不接受 lockVersion/new version。

## 19. JSON trust boundary

共享 JSON reader 拒绝 unknown fields、duplicate keys、trailing JSON、错误 scalar type 和超过 64 KiB 的 body。测试明确覆盖以下伪造字段并确认 Service 未被调用：

`actorUserId`、`role`、`projectRole`、`attemptNo`、`bugKey`、`status`、`projectId`、`runId`、`runCaseId`、`lockVersion`。

响应 DTO 未暴露 password/password hash、session id、DB config、SQL 或内部 relation id。RunCase 无 Attempt 时，`currentOutcome` 明确为 `NOT_RUN`，`latestAttempt` 为 JSON null，不会把“未执行”混同为数据缺失。

## 20. HTTP routing

`ApiServlet` 以显式 segment count、method 与 positive numeric id 路由新增接口。完整匹配避免 `/runs/{id}`、action、cases/attempts，以及 `/defects/{id}`、lifecycle/evidence 路径互相吞并。非法 id 返回 400；额外或缺少 segment（包括 trailing slash 产生的空 segment）不会匹配相邻资源；不支持的方法返回 405 或不存在路径返回 404。未引入通用 router。

## 21. CSRF/header regression and exception mapping

现有过滤器继续要求所有 Round 3 POST/PUT action 携带 `X-QATrack-Request: 1`；有 JSON body 的请求还必须使用 `application/json`。GET 无副作用。未在本轮引入新的 CSRF framework。

异常映射保持：Validation 400、Authentication 401、Forbidden 403、NotFound 404、Conflict/optimistic lock/非法 transition 409、DataAccess 与 unexpected exception 为不泄露内部细节的 500。submission payload conflict 返回 409；并发恢复不向客户端返回原始 1062/1213 或 SQL。

## 22. HTTP workflow evidence

主集成流程的核心业务动作均走 HTTP：login → project → requirement → TestCase → READY → trace link/confirm → TestPlan → READY → TestRun → FAIL Attempt → Defect → IN_PROGRESS → RESOLVED → PASS Attempt → CLOSED。Fixture/DAO 只用于测试账户、membership bootstrap 与最终只读持久化断言。

最终数据库断言确认 FAIL 与 PASS 均保留、attempt number 为 1/2、evidence 指向 FAIL、Defect 为 CLOSED。snapshot、wrong nested ownership、terminal replay、权限、retest close、reopen 与并发幂等均由真实 HTTP→Service→MySQL 测试覆盖。

## 23. Read consistency

Run detail 与 Defect detail 的多次 DAO read 都位于一个 read transaction 内，没有跨事务拼接同一响应，也没有增加无依据的 `FOR UPDATE`。在部署前提 `REPEATABLE-READ` 下，它们获得同一事务一致视图。本次实际隔离实例查询确认 `@@transaction_isolation = REPEATABLE-READ`。

## 24. ApplicationListener and WAR

`ApplicationListener` 本轮只增加 TestRun/TestExecution/Defect Service wiring。既有 JDBC driver 显式加载/注销、ConnectionPool ownership 与 context shutdown cleanup 未改变。

最终验证：

| Check | Result |
| --- | --- |
| `mvn test` | 94 tests，0 failures/errors/skips，PASS |
| `mvn -Pmysql-tests test` | 262 tests，0 failures/errors/skips，PASS |
| `mvn -Pmysql-tests clean package` | 262 tests，BUILD SUCCESS |
| WAR | `target/qatrack-0.1.0-SNAPSHOT.war` |
| WAR SHA-256 | `ECE322E4DFE7C29421D5B44895CC0B423D87279A5B43BA5710B0CB8F8065A0F6` |
| WAR runtime libs | Jackson annotations/core/databind/jsr310 + MySQL Connector/J；无测试类、JUnit/Surefire、local properties、embedded Tomcat 或 Servlet API |
| Standalone WAR | Tomcat 10.1.60，两次独立 deploy/stop；保护 API 返回 401 JSON，PASS |
| MySQL | 8.0.46 / 127.0.0.1:13307 / REPEATABLE-READ |
| Final test schema | `qatrack_test_r1` 为 0 tables |
| Cleanup | temporary MySQL normal shutdown；13307 no listener |
| Development DB | 未连接或修改 `localhost:3306/qatrack` |
| `git diff --check` | PASS |

## 25. Cloud handoff consistency

`docs/CLOUD-DEPLOYMENT-HANDOFF.md` 与实际代码一致，因此本次没有修改。核对事实包括：

- WAR 路径为 `target/qatrack-0.1.0-SNAPSHOT.war`。
- 数据库配置键为 `jdbcUrl`、`username`、`password`、`initialPoolSize`、`maxPoolSize`、`acquireTimeout`。
- 外部配置入口为 `QATRACK_DB_CONFIG` 或 `-Dqatrack.db.config`。
- `QATRACK_SESSION_SECURE=true` 控制 Secure cookie。
- 应用不自动解析 `X-Forwarded-Proto`。
- production 不执行 `seed.sql`。
- ADMIN 需要一次性安全 bootstrap。

当前 WAR 已具备上传真实 ECS 做**受控 smoke deployment**的技术条件。公网开放仍必须完成 handoff 中列出的 HTTPS/Secure Cookie、可信代理、ADMIN bootstrap、限流与 CSRF/Origin 防护。

## 26. Overengineering check

本轮没有新增 generic retry/router/CRUD framework、ORM、repository layer、event bus、CQRS 或 WebSocket。结构保持 Handler → Service → DAO，适合课程答辩。

## 27. Final answers

**A. Phase 4 Round 3 是否批准完成？** 是，批准完成。

**B. 是否存在 commit 前必须修复的问题？** 否。CRITICAL/HIGH/MEDIUM 均为 none。

**C. 是否可以 commit/push？** 可以。Review 本身没有执行 commit/push。

**D. Run / Execution / Defect API 是否足够作为前端核心执行流程的稳定后端？** 是。其核心契约、ownership、历史、并发幂等、状态机和权限已有 HTTP→MySQL 证据。

**E. 当前 WAR 是否可以在 Final Review 后上传真实 ECS 做受控 smoke deployment？** 可以，须按 Cloud handoff 使用生产外部配置、空生产 schema、安全 ADMIN bootstrap 和 HTTPS/Secure Cookie 等部署门禁。

**F. Cloud Deployment Handoff 是否与实际代码一致？** 是，未发现事实冲突，文档未修改。

**G. 是否可以在 commit/push 后开始真实云端 smoke deployment？** 可以，按 handoff 执行受控 smoke；本次 Review 没有连接或修改服务器。

**H. 是否可以进入 Phase 4 Round 4？** 技术上可以，但按当前既定顺序，应先完成 commit/push 与受控云端 smoke，再开始 Round 4；本次未开始 Round 4。

## 28. Git status

Final Review 完成时 staging area 为空。相对 Round 3 实现状态新增：

- `docs/PHASE4-R3-FINAL-REVIEW.md`
- `ExecutionWebTest` 的四个伪造字段覆盖
- `ExecutionHttpIntegrationTest` 的 reopen 直接数据库断言

当前为 12 个已跟踪文件修改、21 个未跟踪文件、0 个 staged 文件。普通 `git diff --stat` 只统计已跟踪文件，为 `12 files changed, 141 insertions(+), 16 deletions(-)`；没有 commit 或 push。

```text
 M src/main/java/io/github/lz007001cn/qatrack/bootstrap/ApplicationListener.java
 M src/main/java/io/github/lz007001cn/qatrack/service/DefaultDefectService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/DefaultTestExecutionService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/DefaultTestRunService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/DefectService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/TestExecutionService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/TestRunService.java
 M src/main/java/io/github/lz007001cn/qatrack/web/ApiServlet.java
 M src/main/java/io/github/lz007001cn/qatrack/web/WebServices.java
 M src/test/java/io/github/lz007001cn/qatrack/integration/TestAssetHttpIntegrationTest.java
 M src/test/java/io/github/lz007001cn/qatrack/web/TestAssetWebTest.java
 M src/test/java/io/github/lz007001cn/qatrack/web/WebFoundationTest.java
?? docs/CLOUD-DEPLOYMENT-HANDOFF.md
?? docs/PHASE4-R3-EXECUTION-DEFECT-API.md
?? docs/PHASE4-R3-FINAL-REVIEW.md
?? src/main/java/io/github/lz007001cn/qatrack/service/query/DefectDetails.java
?? src/main/java/io/github/lz007001cn/qatrack/service/query/RunDetails.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/AddEvidenceRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/AttemptResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/CreateDefectRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/CreateRunRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/DefectDetailResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/DefectResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/RecordAttemptRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/ReopenDefectRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/ResolveDefectRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/RunDetailResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/RunResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/UpdateDefectRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/handler/DefectHandler.java
?? src/main/java/io/github/lz007001cn/qatrack/web/handler/TestRunHandler.java
?? src/test/java/io/github/lz007001cn/qatrack/integration/ExecutionHttpIntegrationTest.java
?? src/test/java/io/github/lz007001cn/qatrack/web/ExecutionWebTest.java
```
