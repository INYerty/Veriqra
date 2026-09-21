# Phase 4 Round 2 — Test Asset REST API

日期：2026-09-20。

## 1. 基线与范围

开始前 working tree clean；main、origin/main 和实际远端 main 均为
267f783b5cc256056de3dcc12f0c1d7574a29a2e（feat(web): add authentication and servlet foundation）。
已重新读取 Round 1 实现与 Final Review，以及实际 Service contract。

本轮仅暴露 Requirement、TestCase/当前 TestStep、Traceability、TestPlan。
没有 Run、Execution、Defect、Automation、Import API，没有前端或新的业务模块。
没有 commit/push。

调用链保持：

HTTP → ApiExceptionFilter → AuthenticationFilter → ApiServlet → Service → DAO → MySQL。

ApiServlet 保留 Auth/Project 路由，把三个资产子路径交给 RequirementHandler、
TestCaseHandler、TestPlanHandler。Traceability 是 RequirementHandler 下的明确子路由。
这些类只解析请求、调用 Service、转换 DTO 和写响应，不访问 DAO/SQL、不管理事务、
不判断项目角色或状态迁移。

## 2. Route table

下表前缀均为 /api/projects/{projectId}，部署 context path 由容器决定。
新增 17 个 method/path 组合；加上原有 Requirement list/create，本表共 19 个。
Round 1 的 Auth/Project API 保持可用，全应用共 25 个 method/path 组合。

| Method | 相对路径 | 成功响应 |
| --- | --- | --- |
| GET | /requirements | 200 RequirementResponse[]（原有） |
| POST | /requirements | 201 RequirementResponse + Location（原有，补充版本字段） |
| GET | /requirements/{requirementId} | 200 RequirementResponse |
| PUT | /requirements/{requirementId} | 200 RequirementResponse |
| GET | /test-cases | 200 TestCaseResponse[] |
| POST | /test-cases | 201 TestCaseResponse + Location |
| GET | /test-cases/{testCaseId} | 200 TestCaseDetailResponse |
| PUT | /test-cases/{testCaseId} | 200 TestCaseResponse |
| GET | /requirements/{requirementId}/test-cases | 200 TraceabilityResponse[] |
| POST | /requirements/{requirementId}/test-cases/{testCaseId} | 204 attach/reattach |
| POST | /requirements/{requirementId}/test-cases/{testCaseId}/confirm | 204 |
| POST | /requirements/{requirementId}/test-cases/{testCaseId}/remove | 204 logical remove |
| GET | /test-plans | 200 TestPlanResponse[] |
| POST | /test-plans | 201 TestPlanResponse + Location |
| GET | /test-plans/{planId} | 200 TestPlanDetailResponse |
| PUT | /test-plans/{planId} | 200 TestPlanResponse |
| POST | /test-plans/{planId}/test-cases/{testCaseId} | 204 add scope |
| POST | /test-plans/{planId}/test-cases/{testCaseId}/remove | 204 remove scope |
| POST | /test-plans/{planId}/archive | 204 |

所有业务 action 统一为 204，不返回 body；调用方 GET 最新资源和 version。
没有单独 Step CRUD、generic relationship CRUD 或硬删除资产 API。
没有新增反向 Traceability 查询，已有需求侧查询足够满足本轮范围。

## 3. DTO 与 JSON contract

新增请求 DTO：

- UpdateRequirementRequest
- CreateTestCaseRequest / UpdateTestCaseRequest / TestStepRequest
- CreateTestPlanRequest / UpdateTestPlanRequest
- ExpectedVersionRequest（计划范围及归档）
- EmptyActionRequest（追踪动作，必须发送空 JSON object）

新增响应 DTO：

- TestCaseResponse / TestCaseDetailResponse，后者内含 TestStepResponse
- TraceabilityResponse
- TestPlanResponse / TestPlanDetailResponse

RequirementResponse 增加 version。没有直接序列化完整 Model。
不暴露 createdBy、passwordHash、数据库连接、内部 lockVersion 字段或 snapshot 字段。
version 是显式的 API concurrency token。

### Requirement

创建示例：

~~~json
{"title":"登录需求","description":"说明","priority":"HIGH"}
~~~

更新示例：

~~~json
{"title":"登录需求","description":"修改后的说明","priority":"HIGH","status":"ACTIVE","expectedVersion":0}
~~~

title、priority 以及更新时 status/expectedVersion 必填且非 null；
description 可省略或为 null。PUT 表达完整可编辑字段集合，省略 description 会写 null，
不是 PATCH 的“保留旧值”。字符串 blank/长度、权限、状态规则仍由 Service 校验。

### TestCase / Steps

创建示例：

~~~json
{
  "title":"有效用户登录",
  "description":"当前定义",
  "preconditions":"已准备可用用户",
  "priority":"HIGH",
  "steps":[{"stepOrder":1,"action":"提交有效凭据","expectedResult":"登录成功"}]
}
~~~

更新携带上述完整可编辑字段、完整 steps，以及：

~~~json
{"status":"READY","expectedVersion":0}
~~~

后一个片段只是增量字段说明，实际 PUT 必须发送完整请求。
采用现有 Service 名称 preconditions（复数）。
stepOrder 显式传入，由 Service 检查从 1 连续；Web 不擅自排序或补号。
steps 必填，可用 [] 创建无步骤 DRAFT；READY 至少一个有效步骤。
null 步骤元素和缺失步骤字段在 JSON 构造阶段为 400。

GET 单个用例返回：

~~~json
{
  "testCase":{"id":20,"projectId":7,"keyNo":1,"title":"有效用户登录","description":"当前定义","preconditions":"已准备可用用户","priority":"HIGH","status":"DRAFT","version":0,"createdAt":"2026-09-20T10:00:00","updatedAt":"2026-09-20T10:00:00"},
  "steps":[{"stepOrder":1,"action":"提交有效凭据","expectedResult":"登录成功"}]
}
~~~

list/create/update 返回 TestCaseResponse，只有单项 GET 包含完整 steps。
GET 的 version 位于 testCase.version，create/update 的 version 位于根节点。

### Traceability

attach/reattach、confirm、remove 都发送 {}，
仍要求 application/json 和 X-QATrack-Request: 1。
未知字段（包括伪造 userId/role）拒绝，不静默忽略。

需求侧查询为数组，每项包含 requirementId、testCase（TestCaseResponse）和 status。
包含 NEEDS_REVIEW、CONFIRMED、REMOVED 三种记录，REMOVED 不代表有效关联。
remove 调用原有 markRemoved 业务流程，不物理删除。
reattach 复用 attach，从 REMOVED 回到 NEEDS_REVIEW。
没有把 isActiveLink/isConfirmedLink 当成数据库记录存在性的替代名称。

### TestPlan

创建：

~~~json
{"name":"回归计划","description":"说明","testCaseIds":[20]}
~~~

testCaseIds 必填；无初始范围时使用 []，而不是 null。
更新元信息或请求 READY：

~~~json
{"name":"回归计划","description":"说明","status":"READY","expectedVersion":0}
~~~

scope add/remove 和 archive：

~~~json
{"expectedVersion":1}
~~~

计划单项 GET 返回 {"plan":TestPlanResponse,"testCaseIds":[...]}，
范围顺序沿用 DAO/冻结模型，不创建不存在的排序字段。
list/create/update 返回 TestPlanResponse。
GET 的版本位于 plan.version，create/update 的版本位于根节点。

## 4. 乐观锁

Requirement/TestCase/TestPlan 更新以及 Plan scope/archive 均显式要求 expectedVersion。
DTO 缺失/null/负数为 400；不能提交 lockVersion、id、keyNo、projectId、createdAt 等字段。
expectedVersion 原样传入已批准的 Service command/方法，不用服务端读取的版本替换。
Service → DAO 的 optimistic conflict 仍为 409。

version 来自实际写入结果的 lockVersion，只用于后续预期版本。
Traceability 原表没有 lock_version，本轮不虚构版本机制；继续使用现有父记录锁和状态规则。

Jackson 关闭 ACCEPT_FLOAT_AS_INT，防止 4.8 被截成 4 后参与乐观锁。
小数 stepOrder/testCaseId 同样拒绝。数字枚举、字符串自动转整数、unknown/duplicate
字段、错误 UTF-8、trailing token 等沿用 Round 1 严格策略。
请求仍最多 64 KiB。

## 5. Path ownership

Web 只解析正整数 projectId/resourceId，必须将 projectId 传入 scoped Service 入口。

Requirement/TestCase/Plan 写方法、Requirement get，以及 Traceability action 增加带 projectId
的重载。ProjectOwnership 在原事务内检查实际 projectId，失败为 ValidationException → 400。
历史不带 scope 的 Service 方法保留，委托同一实现；null scope 仅保留旧调用语义，
HTTP 层没有传 null 的路径。原有用户/成员授权仍执行，不用路径归属校验替代授权。

Case/Plan detail 在 Service 中直接读取 parent、验证权限与项目归属、再读取 children。
Traceability list 还验证每个返回用例的实际项目，错误的跨项目关系不会被序列化返回。

写操作先沿用原来的 project/access/parent 锁与复核，未增加新的 FOR UPDATE，
未改变 Project → User/Member → Requirement/Case → Plan 等既有顺序。
实体 projectId 在冻结 DAO 更新中不可修改，原有父行锁后的同项目复核保留。
不是在 Servlet 做一次预检后再进入另一笔写事务。

真实测试使用可访问两个项目的 ADMIN，验证错误路径上的 get/update/archive、
scope add/remove 和 traceability attach/confirm/remove 全部拒绝，原 version/内容不改变。
不存在“ADMIN 有权限所以路径只是装饰”的绕过。

## 6. 业务边界保持

- Requirement material change → confirmed links NEEDS_REVIEW：原 DefaultRequirementService。
- TestCase 与步骤原子更新、内容变化回 DRAFT、追踪失效、READY/ARCHIVED 规则：
  原 DefaultTestCaseService。
- Traceability attach/confirm/remove/reattach、同项目与已归档资产限制：
  原 DefaultTraceabilityService。
- Plan create、scope、READY、archive：原 DefaultTestPlanService；
  READY 必须范围非空、所有 Case READY、同项目。
- Plan 的 PUT status=ARCHIVED 仍由 Service 拒绝，使用 archive endpoint。
- Scope 改动使 Plan 回 DRAFT 并增加版本，全部由 Service 处理。
- REQ/TC/PLAN Counter 分配、事务所有权、重试政策不变。

HTTP 不操作 Model.status，不触碰 Counter，不自行实现 material-change 判断或状态机。

## 7. 最小 Service 读取扩展

- TestCaseService.getDetails：parent + steps。
- TestPlanService.listByProject。
- TestPlanService.getDetails：parent + plan-case rows。
- TraceabilityService.listByRequirement：case basic information + link state。
- Requirement/TestCase 的 scoped get 与各项 scoped write 重载用于项目归属边界。

新增 TestCaseDetails、TestPlanDetails、TraceabilityDetails 为 Service 读取结果，
不是 Web DTO，也不依赖 Servlet/Jackson。
parent/children 在同一 Service transaction 读取；隔离实例使用 MySQL REPEATABLE-READ。
若部署改变事务隔离级别，需重新评估多语句详情读取的一致性；本轮不修改事务基础设施。

所有查询复用已有 DAO primitive；没有新增/修改 DAO、SQL、索引或 schema。
Traceability list 每个关联读取对应 Case，存在 N+1；课程规模下明确接受，后续按实际量优化。
列表均为简单数组，无分页、排序 DSL 或 generic query framework。

## 8. Auth、Session 和错误边界

全部新路由复用 Round 1 两个 Filter、SessionIdentity、AuthService。
actorUserId 只来自 Session；当前用户状态由 AuthFilter 重读，业务授权由 Service 重读。
DTO 不接收 actorUserId/userId/role/systemRole/projectRole/isAdmin。

所有写请求必须 X-QATrack-Request: 1，有请求体必须 application/json。
同源部署、无宽泛 CORS，未增加 CSRF token、JWT、OAuth、rate limiter 或账号管理。
Cookie/Session 策略和 PBKDF2 不变。

| 结果 | HTTP |
| --- | --- |
| GET / PUT 成功 | 200 |
| create 成功 | 201 + Location |
| 业务动作成功 | 204，无 body |
| JSON/业务 validation/path ownership mismatch | 400 |
| 未认证 | 401 |
| 权限/缺少请求头 | 403 |
| 资源/路由不存在 | 404 |
| method 错误 | 405 + Allow |
| 乐观锁/状态冲突 | 409 |
| 超大 body | 413 |
| 非 JSON media type | 415 |
| DataAccessException/未知异常 | 500，固定安全错误 |

204 之外沿用 JSON UTF-8、ApiError、no-store/nosniff。
不转换已有 Forbidden/NotFound 语义，不返回 SQL、堆栈或原始异常 message。

## 9. 测试证据

保留原 234 项；本轮新增 14 项有状态/边界意义的测试，不按循环断言虚增测试数量：

- TestAssetWebTest：6 项真实 Tomcat HTTP component tests。
  覆盖所有新增类型路由、Session actor/path project/version/完整 steps 的 Service 传参、
  DTO 投影、数组/204、缺失/null/错误类型/小数版本/数字枚举/空元素、
  unknown/duplicate/伪造身份、受保护路径/请求头/method/数值 ID/未知 Step 子路由。
- TestAssetHttpIntegrationTest：8 项真实 HTTP → Service → DAO → MySQL。
  覆盖完整主链、Requirement 追踪失效与版本冲突、Case 步骤替换/回 DRAFT/
  追踪失效/非法步骤与 READY 原子拒绝、Traceability 状态流转与跨项目拒绝、
  Plan scope/READY/归档/冲突、ADMIN 错误项目路径、TESTER/DEVELOPER/失效成员、
  archived Project 写入拒绝与读取保留。

完整主链：
登录 → HTTP 创建 Project → Requirement → Case+Steps → Case READY →
attach → confirm → Plan → Plan READY。
最后通过 DAO 只读核对计划状态、范围、步骤和 CONFIRMED 真实行。
除 user/member bootstrap 外，主链业务状态均通过 HTTP 创建。
归档项目的独立拒绝测试使用原 ProjectService 安排前置状态，因为本轮没有 Project archive API。

Round 1 的 23 项 HTTP component tests 和 3 项 production web.xml/ApplicationListener +
MySQL tests 全部保留，继续覆盖安全 500、不泄露、超大请求、异常映射及真实 Listener wiring。
合计 HTTP component 29 项，HTTP→MySQL 11 项。

MysqlFixture 未修改；新集成测试使用其已验证的 pool，另要求 13307/qatrack_test_*。
不读取应用 DatabaseConfig.load，不受应用 DB 环境覆盖，不连接开发库。
Fixture 安全限制、FK 清理次序、空库校验和最终 drop 保持不变。

## 10. WAR 与基础设施

ApplicationListener 只新增三个现有 Service 的构造注入及 UTC Clock。
JDBC driver discovery/ownership、ConnectionPool close、Connector/J cleanup 和 Session
初始化逻辑没有改变。web.xml、pom.xml/依赖没有改变。

ConnectionPool、JdbcTransactionManager、schema.sql、seed.sql、19 tables/154 fields 均不修改。
JsonHttp 仅提取共享 method 校验，JsonMapperProvider 仅加强浮点到整数的类型拒绝。
AuthService/Filter/Session/PBKDF2 和既有异常映射不变。

由于 Listener wiring 发生变更，package 后沿用独立 WAR launcher 做两次部署/停止。
launcher classpath 只包含 Tomcat embed core；应用类、Jackson、Connector/J 必须来自 WAR。
Smoke 只证明实际 WAR 启动、受保护端点 401 JSON 与 stop/redeploy，
完整业务链路由上面的真实 HTTP→MySQL 集成测试证明，不混淆两者。

## 11. 已知限制与分级

- CRITICAL：none。
- HIGH：none open。
- MEDIUM：本轮受控本地/内网使用没有新增 blocker。
  Round 1 已记录的公网条件仍未解决：登录限流、账号 bootstrap、
  HTTPS/Secure Cookie/可信代理实测，以及完整 CSRF token 或等价严格 Origin 策略。
  按本轮范围没有实现这些功能，不宣称可以直接开放公网。
- LOW：列表无分页；普通用户项目列表及追踪查询有 N+1；
  204 action 后需 GET 新版本，若其间有其他写入，返回的是最新状态而非 action 专属回执。
  详情读取以现有 MySQL REPEATABLE-READ 为前提。
  这些是已说明的课程范围/部署约定，不绕过乐观锁或权限。

本轮没有增加快照、历史版本系统、反向追踪 API、后台作业或其他业务功能。
Round 2 完成后先 Final Review；本轮不开始 Round 3。

## 12. 优先 Final Review 文件

1. DefaultTestPlanService.java：scoped update/archive/scope，保留原锁顺序和版本行为。
2. DefaultTraceabilityService.java：scoped 动作、状态读取、同项目信息边界。
3. RequirementHandler.java：Requirement/Traceability 路由与 DTO/action contract。
4. TestCaseHandler.java：完整步骤输入、expectedVersion 和详情响应。
5. TestAssetHttpIntegrationTest.java：错误路径/权限/状态/原子性实际证据。

同时建议查看 JsonMapperProvider 的单行严格整数调整，以及 DTO 必填字段构造校验。

## 13. 最终验证结果

以下均在最后一次生产代码/测试调整后执行：

| 检查 | 实际结果 |
| --- | --- |
| mvn test | 88 tests，0 failures/errors/skips，BUILD SUCCESS |
| mvn -Pmysql-tests test | 248 tests，0 failures/errors/skips，BUILD SUCCESS |
| mvn -Pmysql-tests clean package | 248 tests，0 failures/errors/skips，WAR BUILD SUCCESS |
| HTTP component | 原 23 + 新 6 = 29 PASS |
| HTTP → MySQL | 原 3 + 新 8 = 11 PASS |
| independent WAR smoke | 两次独立部署/停止 PASS，保护端点 401 JSON |
| WAR content | 全部 Web handler/DTO classes 存在，无测试类/fixture/本地配置/容器重复依赖 |
| git diff --check | PASS |
| isolated MySQL | 8.0.46 / 13307 / REPEATABLE-READ |
| test schema | qatrack_test_r1 最终 0 tables |
| cleanup | mysqladmin shutdown 成功；临时进程退出；13307 no listener |
| development database | 未连接或修改 localhost:3306/qatrack |
| frozen/schema/infrastructure | schema、seed、ConnectionPool、JdbcTransactionManager、MysqlFixture、pom、web.xml 均无 diff |

WAR：target/qatrack-0.1.0-SNAPSHOT.war。
WEB-INF/lib 仅有：

- jackson-annotations-2.21.jar
- jackson-core-2.21.6.jar
- jackson-databind-2.21.6.jar
- jackson-datatype-jsr310-2.21.6.jar
- mysql-connector-j-9.7.0.jar

没有 Servlet API、embedded Tomcat、JUnit、Surefire、test classes、fixture 或 local properties。
独立 smoke 使用只含 Tomcat embed core 的 launcher classpath，并启用容器
ThreadLocal/RMI 检查所需的 JDK add-opens。

本机验证仍使用 Round 1 记录的测试 JVM 参数：
-DargLine=-Djdk.net.unixdomain.tmpdir=<NONEXISTENT_SOCKET_DIR>。
用于本机 JDK Windows socket workaround，没有改 PATH 或提交机器配置。

本地原始日志保留在仓库外的 <VALIDATION_ROOT>：

- phase4-r2-final-test.log
- phase4-r2-final-mysql-test.log
- phase4-r2-final-package.log
- phase4-r2-final-war-smoke.log

## 14. Actual Git state

HEAD 和 origin/main 仍为 267f783b5cc256056de3dcc12f0c1d7574a29a2e。
16 个 tracked modified 文件，23 个 untracked 新文件；staging area 为空。
未 commit/push。下面 git diff --stat 默认不计入 untracked 新文件。

~~~text
 .../qatrack/bootstrap/ApplicationListener.java     |  5 ++-
 .../qatrack/service/DefaultRequirementService.java | 10 +++++
 .../qatrack/service/DefaultTestCaseService.java    | 23 +++++++++++
 .../qatrack/service/DefaultTestPlanService.java    | 47 +++++++++++++++++++++-
 .../service/DefaultTraceabilityService.java        | 39 ++++++++++++++++--
 .../qatrack/service/RequirementService.java        |  2 +
 .../qatrack/service/TestCaseService.java           |  3 ++
 .../qatrack/service/TestPlanService.java           |  6 +++
 .../qatrack/service/TraceabilityService.java       |  4 ++
 .../github/lz007001cn/qatrack/web/ApiServlet.java  | 25 +++++-------
 .../io/github/lz007001cn/qatrack/web/JsonHttp.java |  6 +++
 .../github/lz007001cn/qatrack/web/WebServices.java |  4 +-
 .../qatrack/web/dto/CreateRequirementRequest.java  |  7 +++-
 .../qatrack/web/dto/RequirementResponse.java       |  4 +-
 .../qatrack/web/json/JsonMapperProvider.java       |  1 +
 .../lz007001cn/qatrack/web/WebFoundationTest.java  |  5 ++-
 16 files changed, 163 insertions(+), 28 deletions(-)
~~~

git status --short --untracked-files=all：

~~~text
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
~~~

## 15. 本轮结论

Phase 4 Round 2 实现及上述验证完成，可以进入针对性的 Final Review。
没有发现本轮 commit 前必须修复的 CRITICAL/HIGH 或代码级 MEDIUM；
公网部署限制仍按第 11 节保留。未进行 commit/push，未开始 Round 3。
