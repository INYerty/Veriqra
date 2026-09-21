# Phase 4 Round 2 Final Review

日期：2026-09-21。审查范围仅为 Phase 4 Round 2 — Test Asset REST API。基线为 `267f783 feat(web): add authentication and servlet foundation` 加当前未提交 Round 2 working tree。本轮不进入 Round 3，不修改 schema，不 commit/push。

## 1. Final verdict

**批准 Phase 4 Round 2。** 当前没有 CRITICAL、未关闭 HIGH 或 commit 前阻塞 MEDIUM。Test Asset API 已具备支撑前端第一批项目内需求、用例/步骤、追踪关系和测试计划页面的稳定后端契约。

- CRITICAL：none。
- HIGH：none。
- MEDIUM（已修复）：`expectedVersion` 原先拒绝缺失、null、小数和超范围整数，但负数会进入 Service 并表现成 409。数据库版本不可能为负数，现已在四个版本请求 DTO 的 JSON 构造边界拒绝负数并返回 400，并补对应测试。
- MEDIUM（open）：none。
- LOW：列表没有分页；Traceability 查询按关联逐条读取 TestCase，存在 N+1；action 返回 204 后客户端需要 GET 最新版本。这些在课程项目规模下不影响 correctness/security。
- INFORMATIONAL：Round 1 记录的公网部署项仍未完成，包括登录限流、账号 bootstrap、HTTPS/Secure Cookie/可信代理实测，以及完整 CSRF token 或严格 Origin 方案；不阻塞本轮受控环境和前端开发。

## 2. Route coverage

前缀均为 `/api/projects/{projectId}`。Round 2 新增 17 个 method/path 组合；Requirement collection 的 GET/POST 来自 Round 1，本表共 19 个资产组合。

| Method | Path | Success | Service boundary |
| --- | --- | ---: | --- |
| GET/POST | `/requirements` | 200/201 | list/create |
| GET/PUT | `/requirements/{requirementId}` | 200 | scoped get/update |
| GET/POST | `/test-cases` | 200/201 | list/create with complete steps |
| GET/PUT | `/test-cases/{testCaseId}` | 200 | scoped getDetails/update |
| GET | `/requirements/{requirementId}/test-cases` | 200 | scoped traceability list |
| POST | `/requirements/{requirementId}/test-cases/{testCaseId}` | 204 | attach/reattach |
| POST | `.../{testCaseId}/confirm` | 204 | confirm |
| POST | `.../{testCaseId}/remove` | 204 | logical remove |
| GET/POST | `/test-plans` | 200/201 | list/create + initial scope |
| GET/PUT | `/test-plans/{planId}` | 200 | scoped getDetails/update |
| POST | `/test-plans/{planId}/test-cases/{testCaseId}` | 204 | addCase |
| POST | `.../{testCaseId}/remove` | 204 | removeCase |
| POST | `/test-plans/{planId}/archive` | 204 | archive |

不存在独立 Step CRUD、generic relation CRUD、hard-delete Traceability、generic status PATCH，也不存在 `/test-cases/{id}/requirements` 或 `/test-plans/{id}/cases/{id}` 等相邻误路由。

明确的已知路由返回 405 + Allow；未知前缀/多余 segment 返回 404；非法、空或溢出的 path ID 安全返回 400/404，不会进入 Service。路由是显式 switch/长度判断，没有 reflection、generic router 或自制 MVC framework。

## 3. Architecture boundary

实际调用链保持：

```text
HTTP → ApiExceptionFilter → AuthenticationFilter
     → ApiServlet / small Handler → Service → DAO → JDBC / MySQL
```

RequirementHandler、TestCaseHandler、TestPlanHandler 只做 method/path/body 解析、DTO 转换、接收 Session actor、调用 Service、写 response。搜索和逐行审查未发现 DAO/JdbcDao、SQL、Connection、transaction、ProjectRole/Counter、READY/同项目/状态机/步骤连续性规则。Handler 不捕获业务异常后继续写入。

Traceability 是 RequirementHandler 下的嵌套 HTTP adapter，没有单独创建无价值的 Handler。没有 Controller 基类、generic CRUD、repository abstraction 或 DTO mapper framework。

## 4. Nested path ownership

所有 HTTP 资产方法都把 positive projectId 传给 scoped Service。Servlet/Handler 不比较实体 projectId。

Service 在同一读取/写事务中读取真实实体、执行当前用户/成员权限检查，并通过 ProjectOwnership 检查实际 projectId 与 path：

- Requirement：scoped get/update；
- TestCase：scoped update/getDetails；
- TestPlan：getDetails/update/archive/addCase/removeCase；
- Traceability：双端先验证同项目，再验证该项目等于 path；查询也验证 Requirement 和每个返回 Case。

实体 projectId 在冻结 DAO 更新语义中不可修改，锁定后保留原同项目复核。真实 MySQL 测试使用能访问两个项目的 ADMIN，证明错误路径上的 Requirement、TestCase、TestPlan、scope 和 Traceability 全部拒绝，内容/version 不变。

普通用户若无权读取实际所属项目，原 Service 的 Forbidden 可先于 path mismatch；Handler 不擅自改写 Forbidden/NotFound/Validation 语义。

## 5. Requirement review

Create/Update DTO 只包含 caller 可编辑字段；id、projectId、keyNo、createdBy、时间和 lockVersion 都不是字段，提交后由 unknown-property 规则返回 400。

更新显式要求 expectedVersion，成功响应暴露 version token；stale update 经 OptimisticLockException → ConflictException → 409。material change 仍只由 Service 根据 title/description 判断，CONFIRMED → NEEDS_REVIEW 与 Requirement 更新位于同一事务。Priority/status 的非实质更新不会错误 invalidation，Phase 3 集成测试有直接证据；REMOVED link 不会被重新激活。Archived Requirement/Project 和错误 path 仍由 Service 拒绝。

## 6. TestCase / Step review

TestCase 与当前 TestStep 仍是一个业务整体。Create/Update body 携带完整 steps；Handler 只把 TestStepRequest 转为 TestStepInput；Service 校验从 1 连续、内容非空，并在一个事务内更新 Case、替换 Steps、回 DRAFT、使确认追踪 NEEDS_REVIEW。任一步骤写入或 invalidation 失败会完整回滚。

没有 `POST/PUT/DELETE /steps`。空 steps 可用于 DRAFT；READY + 空 steps、gap/duplicate order、null step、错误 shape、小数 order 都被拒绝。语义内容变化即使请求 READY，也由 Service 持久化为 DRAFT；ARCHIVED 当前定义不能通过普通 update 恢复。

当前定义更新不触碰 test_run_cases/test_run_case_steps。既有集成测试证明 Run snapshot title/step 在当前 TestCase 修改后保持不变；Web 层没有历史同步代码。

## 7. Traceability review

状态流转仍由 Service 保证：

```text
new attach → NEEDS_REVIEW
NEEDS_REVIEW → confirm → CONFIRMED
active → remove → REMOVED
REMOVED → attach/reattach → NEEDS_REVIEW
```

remove endpoint 明确命名为 `/remove`，DAO 执行状态更新而非 DELETE。REMOVED 不能 confirm；reattach 更新同一复合主键行并保留最初 linkedBy/linkedAt，不插入第二行。双端必须同项目且等于 path，任一 archived endpoint 或 archived Project 阻止写入。

需求侧查询明确返回所有数据库关系状态，包括 REMOVED，并显式返回 status；不会把 REMOVED 伪装成物理不存在或有效覆盖。N+1 是 LOW 性能项，每个 Case 仍逐项验证同项目，不是权限漏洞。

## 8. TestPlan review

创建仍由 Service 单事务分配 PLAN counter、锁定/校验 Cases、插入 Plan 和 scope。Handler 不访问 PlanCase DAO，也不判断同项目、Case READY 或 Plan status。

Scope add/remove 携带 expectedVersion。Service 锁定 Case/Plan，检查 scope，用 Plan optimistic update 推进 version 并置为 DRAFT，再写关系表；失败整体回滚。

已固定并验证：stale → 409；duplicate add → 409 且不推进版本；remove missing → 404 且不推进版本；cross-project/archived Case rejected；scope change → DRAFT + version 增加。

READY 仍由 Service 校验 scope 非空、全部 Case READY、同项目，并在锁定后复核 scope。PUT status=ARCHIVED 被拒绝，只能调用 archive。ARCHIVED 后 update/add/remove 均为 409。

## 9. Optimistic locking

统一契约为 request `expectedVersion`、response `version`；内部 lockVersion 不暴露。覆盖 Requirement/TestCase/TestPlan update，以及 Plan add/remove/archive。客户端不能提交新版本。

| 输入 | HTTP |
| --- | ---: |
| missing / null | 400 |
| negative | 400 |
| fractional / string | 400 |
| outside Integer range | 400 |
| duplicate property | 400 |
| valid non-negative but stale | 409 |

负数校验是本次 Final Review 唯一 production 修复，只收紧 Web DTO，不修改 Phase 3 Service/DAO。

## 10. JSON trust boundary and enums

全部新 DTO 使用同一个 JsonMapperProvider：unknown、duplicate、trailing token、numeric enum、scalar coercion 均拒绝；关闭 ACCEPT_FLOAT_AS_INT，防止版本号/stepOrder 截断。

actorUserId、userId、role、projectRole、isAdmin、projectId、keyNo、lockVersion 不是 request DTO 字段，不能用于授权或定位，提交即 400。actor 唯一来源仍是 Session。

Priority、RequirementStatus、TestCaseStatus、TestPlanStatus 使用区分大小写的字符串；unknown/case mismatch/numeric ordinal 均为安全 400，Jackson 原始异常不回显。

## 11. Authentication and Session regression

全部新 route 位于 `/api/*`，经过 Round 1 两个 Filter。Handler 只接收 SessionIdentity 的 actor，不缓存 role/membership。

真实测试证明：未登录 401；TESTER 可写；DEVELOPER 可读但写 403；membership 失效后旧 Session 读写拒绝；archived Project 可读但写入 409。Round 1 的 DISABLED user、role 变化、Session 轮换测试全部继续通过。

## 12. CSRF/header regression

AuthenticationFilter 对全部非 GET/HEAD/OPTIONS 统一要求 `X-QATrack-Request: 1`，所以 POST、PUT 和 remove action 无遗漏。全部写路由都有 JSON body并强制 application/json。GET 只调用读取能力，无副作用。

当前仍是同源、无宽泛 CORS、custom-header + strict JSON + SameSite=Lax 基线；没有添加或弱化 CSRF framework、JWT/OAuth 或 rate limiter。

## 13. HTTP status and response DTO

GET/PUT 成功 200，create 201 + Location，业务 action 204 无 body。错误由 Filter 统一映射：400/401/403/404/405/409/413/415/500；optimistic stale 为 409，wrong project 保留 Service 的 Forbidden/NotFound/Validation 语义。

响应不含 password/hash、Session ID、DB config、createdBy/reviewedBy、内部 lockVersion 或完整 Model。`version` 是 API token。Steps 按 step_order；Plan scope 按 Case keyNo/id；Traceability 显式返回 status。

## 14. Read consistency and Service capability

新增 TestCaseService.getDetails、TestPlanService.listByProject/getDetails、TraceabilityService.listByRequirement 和 scoped ownership overload，均属 Service 合理读取职责。

getDetails 在一个 ServiceTransaction callback 中创建绑定同一 Connection 的 DAO，验证 parent/权限/归属后读取 children。隔离实例实测 REPEATABLE-READ，因此 parent/children 来自同一 transaction snapshot；没有跨连接拼接或新增 FOR UPDATE。列表先验证目标 Project 与权限，再按 project_id 查询。

## 15. HTTP → MySQL main workflow

核心契约链：

```text
Login → create Project → create Requirement → create TestCase + Steps
      → TestCase READY → attach → confirm → create TestPlan → Plan READY
```

核心业务状态全部通过 HTTP 建立。DAO 只用于 user/member fixture bootstrap 和链路结束后的只读断言。最终断言覆盖 Plan READY、scope、Step 和 CONFIRMED link。

归档 Project 专项测试因本轮没有 Project archive API，使用已有 ProjectService 安排前置状态；不污染主链。

## 16. Transaction regression

Web 不创建/提交/回滚/关闭 Connection。Phase 3 ServiceTransaction 仍是唯一所有者。既有与本轮测试证明 Requirement update + invalidation、Case + Steps + invalidation、Plan counter + Plan + scope、Plan version/status + scope、Traceability 状态变化均原子；注入第 N 次写失败会完整回滚。

## 17. WAR and deployment

Round 2 对 ApplicationListener 的改动仅为注入 TestCaseService、TraceabilityService、TestPlanService 和 UTC Clock。Driver lifecycle、pool close、Session 初始化未改；web.xml、pom dependencies 无 diff。

WAR 包含新 Handler/DTO；WEB-INF/lib 只有 Jackson 四个 JAR 与 Connector/J，不含 test classes、JUnit、fixture、local properties、embedded Tomcat、Servlet API 或 test config。当前 WAR 再次完成两次 startup → 401 JSON → stop/redeploy，全部 PASS。

## 18. Performance and scope limitations

LOW/INFORMATIONAL：资产列表无分页；Traceability 和 USER Project list 有 N+1；204 action 后需 GET 最新资源/version；没有反向 Traceability 查询。课程规模下可保留。Final Review 未引入 Page、query DSL、ORM、cache、generic repository/router。

## 19. Test results

本次修复和测试补强后重新执行：

| Check | Result |
| --- | --- |
| `mvn test` | 88 PASS |
| `mvn -Pmysql-tests test` | 248 PASS |
| `mvn -Pmysql-tests clean package` | 248 PASS，WAR BUILD SUCCESS |
| HTTP component | 29 PASS（23 + 6） |
| HTTP → MySQL | 11 PASS（3 + 8） |
| WAR content | PASS |
| standalone WAR | 两次部署/停止 PASS |
| `git diff --check` | PASS |
| isolated MySQL | 8.0.46 / 13307 / REPEATABLE-READ |
| test schema | 最终 0 tables |
| cleanup | temporary mysqld shutdown；13307 no listener |
| development DB | 未连接或修改 localhost:3306/qatrack |

测试数量保持 88/248：补强写在现有 6 项 component 和 8 项 integration test 内，没有为数字拆分测试。

## 20. Frozen files and actual changes

无 diff：schema.sql、seed.sql、ConnectionPool、JdbcTransactionManager、MysqlFixture、pom.xml、web.xml。未新增 DAO、SQL、索引或数据库对象。

Final Review 实际修改：

- 四个 expectedVersion request DTO：负数拒绝；
- TestAssetWebTest：负数/超范围版本；
- TestAssetHttpIntegrationTest：duplicate scope add 409、missing scope remove 404；
- PHASE4-R2-TEST-ASSET-API.md：同步版本边界；
- 本文档。

## 21. Git status

HEAD 和 origin/main 仍为 `267f783b5cc256056de3dcc12f0c1d7574a29a2e`。
staging area 为空；16 个 tracked 文件 modified，24 个新文件 untracked。
`git diff --stat` 为 `16 files changed, 163 insertions(+), 28 deletions(-)`，
该统计不包含 untracked 文件。

```text
 M src/main/java/io/github/lz007001cn/qatrack/bootstrap/ApplicationListener.java
 M src/main/java/io/github/lz007001cn/qatrack/service/DefaultRequirementService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/DefaultTestCaseService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/DefaultTestPlanService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/DefaultTraceabilityService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/RequirementService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/TestCaseService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/TestPlanService.java
 M src/main/java/io/github/lz007001cn/qatrack/service/TraceabilityService.java
 M src/main/java/io/github/lz007001cn/qatrack/web/ApiServlet.java
 M src/main/java/io/github/lz007001cn/qatrack/web/JsonHttp.java
 M src/main/java/io/github/lz007001cn/qatrack/web/WebServices.java
 M src/main/java/io/github/lz007001cn/qatrack/web/dto/CreateRequirementRequest.java
 M src/main/java/io/github/lz007001cn/qatrack/web/dto/RequirementResponse.java
 M src/main/java/io/github/lz007001cn/qatrack/web/json/JsonMapperProvider.java
 M src/test/java/io/github/lz007001cn/qatrack/web/WebFoundationTest.java
?? docs/PHASE4-R2-FINAL-REVIEW.md
?? docs/PHASE4-R2-TEST-ASSET-API.md
?? src/main/java/io/github/lz007001cn/qatrack/service/TestCaseDetails.java
?? src/main/java/io/github/lz007001cn/qatrack/service/TestPlanDetails.java
?? src/main/java/io/github/lz007001cn/qatrack/service/TraceabilityDetails.java
?? src/main/java/io/github/lz007001cn/qatrack/service/support/ProjectOwnership.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/CreateTestCaseRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/CreateTestPlanRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/EmptyActionRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/ExpectedVersionRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/TestCaseDetailResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/TestCaseResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/TestPlanDetailResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/TestPlanResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/TestStepRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/TraceabilityResponse.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/UpdateRequirementRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/UpdateTestCaseRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/dto/UpdateTestPlanRequest.java
?? src/main/java/io/github/lz007001cn/qatrack/web/handler/RequirementHandler.java
?? src/main/java/io/github/lz007001cn/qatrack/web/handler/TestCaseHandler.java
?? src/main/java/io/github/lz007001cn/qatrack/web/handler/TestPlanHandler.java
?? src/test/java/io/github/lz007001cn/qatrack/integration/TestAssetHttpIntegrationTest.java
?? src/test/java/io/github/lz007001cn/qatrack/web/TestAssetWebTest.java
```

## 22. Final answers

- A. Phase 4 Round 2 是否批准：**是**。
- B. commit 前必须修复的问题：**none**。
- C. 是否可以 commit/push：**可以**；本轮没有执行。
- D. 是否足够作为前端第一批页面的稳定后端：**是**。前端应遵守完整 PUT、expectedVersion/version、204 后重新 GET。
- E. 是否可以进入 Phase 4 Round 3：**可以**，先完成本轮人工 Review 与 commit/push；本轮未开始 Round 3。
