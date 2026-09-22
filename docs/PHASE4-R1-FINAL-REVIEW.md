# Phase 4 Round 1 Final Review

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

日期：2026-09-20。审查范围仅为 Authentication、Servlet Foundation、JSON Error Model，以及用于验证 Web→Service 模式的 Project / Requirement 首批 API。基线为 `5c196c1 feat(service): complete core business service layer` 加当前未提交 Round 1 working tree；`HEAD == origin/main`。本轮不进入 Round 2，不修改 schema，不 commit/push。

## 1. Final verdict

**批准 Phase 4 Round 1。** 当前没有 CRITICAL、未关闭 HIGH 或 commit 前阻塞 MEDIUM。架构保持 HTTP → Servlet / Filter → Service → DAO → JDBC；当前 WAR 已具备在真实 Tomcat 10.1 上进行非公开、受控 smoke deployment 的条件。

问题分级：

- CRITICAL：none。
- HIGH：none open。Round 1 曾发现独立 WAR 中 Connector/J 在父 classloader 已先初始化 DriverManager 时未被自动发现；已由 ApplicationListener 显式加载并管理 driver lifecycle，独立 WAR 连续两次部署/停止验证通过。
- MEDIUM（public deployment blockers，不阻塞本轮 commit）：登录限流/抗爆破、可审计的一次性账号 bootstrap、HTTPS/TLS 与 Secure Cookie/可信反向代理实测、完整 CSRF token 或等价严格 Origin 策略。
- MEDIUM code blocker：none。
- LOW：普通用户 Project list 为 membership 列表后逐项取 Project，存在 N+1 且无分页；500 日志没有 requestId；已提交响应后的网络写失败只能记录，无法可靠改写成 JSON 500。
- INFORMATIONAL：unknown-user dummy PBKDF2 与存量 hash 如果使用不同 iteration，不能宣称严格恒定响应时间；当前错误文本和业务行为不区分账号不存在、密码错误或 DISABLED。

## 2. HTTP architecture

实际调用链为：

```text
HTTP
  → ApiExceptionFilter
  → AuthenticationFilter
  → ApiServlet
  → Service interface
  → DAO interface
  → JDBC / MySQL
```

ApiServlet 只解析 HTTP/path/JSON、从 Session 获取 actorUserId、调用 Service 并写响应。Web 包未导入 DAO/JdbcDao、未写 SQL、未开启或提交事务。AuthenticationFilter 只建立认证边界，不判断项目角色或业务权限。

ApplicationListener 是允许接触 DatabaseConfig、ConnectionPool 和 JDBC driver 的 composition root；它不是请求层。Service/DAO 搜索未发现 jakarta.servlet、HttpServletRequest、HttpSession 或 HTTP status 依赖。DAO 不知道 HTTP。没有 `javax.servlet.*`，全部为 Jakarta Servlet 6。

## 3. Authentication review

DefaultAuthService 通过 ServiceTransaction 和 ServiceDaoFactory 调 UserDao；Servlet 不直接读取密码。

- username 必须为 1..64 个可打印 ASCII 字符，和冻结 schema 的 ascii username 一致。
- password 必须非空且不超过 1024 个 Java char。
- 不存在账号、错误密码、DISABLED 使用同一个 AuthenticationException 和同一安全 HTTP 401 响应。
- DISABLED 用户仍先完成真实 hash 校验再失败，避免直接状态分支形成明显差异。
- PBKDF2 在事务外计算，不长时间占用 JDBC connection。
- 密码验证后开启新事务，按 user id 重新读取并复核 status 和 passwordHash，拒绝计算期间发生的禁用或换密竞态。
- AuthenticatedUser 只含 id、username、systemRole、status；无 hash。UserResponse 同样无 hash/password。
- `/me` 和每个受保护请求均通过 AuthService.current 读取当前真实 User；登录后变为 DISABLED 的旧 Session 会收到 401，并由 AuthenticationFilter invalidate。

23 项 HTTP component tests 与 3 项 HTTP→Service→MySQL tests 覆盖错误密码、不存在用户、DISABLED、登录、`/me`、禁用后的旧 Session、ADMIN 降权和 membership 失效。

## 4. PBKDF2 review

PasswordVerifier 固定使用 JDK `PBKDF2WithHmacSHA256`，解析冻结格式：

```text
pbkdf2_sha256$iterations$hexSalt$hexDigest
```

约束为 iteration 210000..1000000、salt 12..64 bytes（24..128 hex chars）、derived key 32 bytes（64 hex chars）。HexFormat 对非 hex 和奇数长度 fail closed；字段数、算法名、整数、salt、digest 任一不合法均返回 false。iteration 上限避免数据库中恶意/损坏 hash 触发无界 CPU 消耗。密码输入也有长度上限。

派生结果使用 MessageDigest.isEqual 比较，不用 String.equals；PBEKeySpec、临时 char[] 和 derived bytes 用后清理。不记录 raw password/hash，不使用 MD5/SHA-1，不自创加密算法，不引入另一套密码库。

未知用户/非法 hash 执行固定 dummy PBKDF2 后失败。该措施减少快速失败差异，但不声称网络上严格恒定时间：如果未来账号 hash 成本不同于 dummy，仍会存在统计差异。因此公网部署还需要登录限流；未来升级 hash 成本时应同步 dummy 参数或使用统一认证成本策略。

## 5. Session security

Session 只保存一个 Long actorUserId。不会保存 systemRole、projectRole、membership status、isAdmin、完整 User 或 passwordHash。

成功登录先 invalidate 任何旧 Session，再创建新 Session、写入 actorUserId，并设置 30 分钟 idle timeout。旧 Session id 无法继续访问。账号切换不会保留旧属性。认证失败发生在写 Session 之前，不污染已有登录身份。Logout invalidate；旧 cookie 后续为 401。

COOKIE 是 web.xml 中唯一 tracking mode；URL `;jsessionid=` 不接受为登录态。实际 Tomcat 测试覆盖登录前既有 Session、成功轮换、旧 ID、logout 与 URL Session ID。伪造 actorUserId/systemRole 等 unknown JSON 字段为 400，无法成为可信身份。

每个请求的 User 状态由 AuthService.current 重读；每个业务 Service 继续从 DB 重读实际 role/membership。测试证明 ACTIVE→DISABLED、ADMIN→USER、membership ACTIVE→INACTIVE 后，旧 Session 不能保留旧权限。

## 6. AuthenticationFilter and path boundary

web.xml 将 AuthenticationFilter 映射到 `/api/*` 且只处理 REQUEST dispatcher。精确的 `/api/auth/login` 是唯一匿名资源；Servlet 仅允许其 POST，其他 method 为 405。`/api/auth/me` 和 logout 未登录均为 401 JSON，Filter 不 redirect HTML。

匿名判定由同一 HttpServletRequest 的 servletPath + pathInfo 完成；ApiServlet 使用同一容器已解析 pathInfo 路由，不执行第二次 URL decode。`/api//...`、`/api/auth/login/` 不等于匿名路径；非法数字 ID 为 400，未知或多余 path segment 为 404。encoded path 的规范化/拒绝由 Tomcat 完成，应用不对已解码路径再 decode，避免双重解码。

当前无 async；web.xml 没有 async-supported，也没有 ASYNC/ERROR filter mapping。ERROR dispatch 不进入两层 API Filter，因此不会递归生成错误响应。

## 7. Filter ordering

web.xml 的 mapping 顺序为 ApiExceptionFilter，然后 AuthenticationFilter，再进入 ApiServlet。因此 AuthenticationFilter 抛出的 AuthenticationException/HttpFailure 会由统一异常边界转换成 JSON。实际 production web.xml + Tomcat + MySQL 测试覆盖 logout 后 `/me` 的 401；独立 WAR smoke 对未登录 `/me` 验证 401 和 `UNAUTHENTICATED` JSON，证明不是 embedded test 替身造成的假象。

## 8. Exception mapping

| Failure | HTTP | Stable code |
| --- | ---: | --- |
| ValidationException / malformed input | 400 | VALIDATION / INVALID_JSON / INVALID_ID |
| AuthenticationException | 401 | UNAUTHENTICATED |
| ForbiddenException | 403 | FORBIDDEN |
| NotFoundException / unknown route | 404 | NOT_FOUND |
| wrong method | 405 | METHOD_NOT_ALLOWED + Allow |
| ConflictException | 409 | CONFLICT |
| oversized JSON | 413 | PAYLOAD_TOO_LARGE |
| wrong media type | 415 | UNSUPPORTED_MEDIA_TYPE |
| DataAccessException / unexpected failure | 500 | INTERNAL_ERROR |

客户端只收到固定 code/message，不含 SQLException、SQL、数据库 URL/username、异常 message、stack trace 或请求内容。500 服务端记录异常类型和栈帧，不记录 message/cause，避免 JDBC/Jackson message 携带秘密。测试覆盖全部业务映射、DataAccessException、RuntimeException、Error，并断言客户端无 secret/SQLException/stackTrace。

Filter 在响应未 committed 时 resetBuffer 并写安全 JSON；已 committed 时不 reset、不重复写。JsonHttp 在发送成功响应前先完成序列化，使应用级 serialization failure 通常仍可安全变成 500。网络断开或已经提交后的传输失败不能被 HTTP 层可靠改写，这是 Servlet 响应生命周期限制。

## 9. JSON boundary

JsonMapperProvider 只有一个完成配置后不再修改的 ObjectMapper，对外返回 thread-safe immutable reader/writer。Java Time 使用 JavaTimeModule，LocalDateTime 输出 ISO-8601 字符串，不输出 epoch timestamp。

输入要求 `application/json`；media type 大小写与参数不会被错误拒绝，字符始终按严格 UTF-8 解码。GET 不要求 Content-Type。`text/plain` 等明显错误类型为 415，没有 form-urlencoded/JSONP fallback。

Jackson 开启 unknown-field、trailing-token、duplicate-field、numeric-enum 拒绝，并关闭 scalar coercion。malformed、非法 UTF-8、错误字段类型、数组代替对象、null root 均为固定 400。

请求体通过 `readNBytes(65537)` 实际最多读取 64 KiB + 1 byte 后判断；不依赖可伪造或缺失的 Content-Length，也不会先把无限 body 读入内存。另有限制 JSON nesting depth 32、string 65536、number text 32。

## 10. CSRF current protection

所有当前 state-changing endpoint 都是 POST，并全部经过 AuthenticationFilter 的 `X-QATrack-Request: 1` 校验：login、logout、Project create、Requirement create。GET 只有读取或当前认证状态检查，不改变业务状态。

当前课程项目假设 UI/API 同 origin；没有 CORS allow header。浏览器跨 origin 脚本若要发送自定义 header 会触发 preflight，而服务不批准 preflight。普通 HTML form 无法设置该 header，且 body 写接口还要求 application/json。因此当前 header gate + strict JSON + SameSite=Lax 对同源课程项目是可接受基线。Login 也受 header gate，防止普通 form 触发 login CSRF；logout 同样受保护。

它**不等价于完整 CSRF token**。curl、受信任但被攻陷的同源脚本、错误放开的代理/CORS 配置都能设置该 header。公网开放前应加入同步 token/double-submit 等正式机制，或至少严格 Origin/Host allowlist 并保留当前 header；若未来开放 CORS、表单入口或跨 origin UI，必须在部署前重做威胁模型。

## 11. Cookie policy

- HttpOnly：web.xml session cookie config。
- SameSite=Lax：ApplicationListener 通过 Servlet 6 SessionCookieConfig 设置，并已从真实 Tomcat Set-Cookie 验证。
- Path：由 Tomcat 按 WebApp context path 管理；本项目部署 `/qatrack` 时 session cookie 限定该 context，不自行写宽泛 `/` Cookie。
- Secure：`QATRACK_SESSION_SECURE=true` 时启用；只接受精确 true/false。未配置/false 允许本地 HTTP smoke 正常使用。
- Tracking：仅 COOKIE，不把 Session ID 写进 URL。

生产 HTTPS 必须启用 Secure，并实测 TLS termination 与可信反向代理 scheme 配置。应用没有读取任意 X-Forwarded-* 来自行判断安全性。

## 12. CORS

生产代码没有 CORS Filter，也没有 `Access-Control-Allow-Origin` 或 credentials 放行。测试确认 preflight 响应不包含 Allow-Origin。当前同源 UI 场景不需要 CORS，尤其不存在 `Access-Control-Allow-Origin: *` 与 credentials 组合。

## 13. Login rate limiting

当前没有登录频率限制、失败计数或账号锁定。为课程内本机/内网受控 smoke deployment，它不阻塞 Round 1 封板，也不需要引入 Redis/distributed limiter。

它是**公网部署 blocker**：在真正开放互联网前至少需要按远端来源和 username 组合的有界限流、失败响应一致性、代理来源可信配置和可观测性；策略应避免攻击者通过锁定账号造成简单 DoS。该任务留给后续 Web security round。

## 14. Deploy account/bootstrap requirement

冻结 seed 的 password hash 来自已丢弃随机输入，没有已知明文密码。这不是 Auth 代码错误，但它是任何需要实际登录的部署/答辩的 operational blocker。

部署前必须通过一次性本地/服务器 bootstrap 命令，或由管理员离线生成兼容 PBKDF2 hash 后更新目标数据库，准备至少一个已知密码的 ACTIVE ADMIN。不得把默认明文密码、可恢复凭据或固定演示 admin 密码提交到 seed/GitHub。Round 1 不实现注册、密码重置或用户管理系统。

## 15. ApplicationListener lifecycle

启动顺序：验证 Session Secure 配置 → 用父 loader 上下文初始化/记录已有 Driver → 显式 `Class.forName` WebApp 内 Connector/J → 记录仅由本 WebApp 新注册的 Driver → DatabaseConfig.load → ConnectionPool → JdbcTransactionManager/Service adapter → Service wiring → ServletContext attribute。

若配置、driver、pool 或 wiring 中途失败，catch 调用统一清理：已创建 pool 真正关闭物理连接，随后处理 owned Connector/J cleanup thread 并 deregister owned driver。对外抛固定初始化失败消息，不附带可能含凭据的 cause。

停止时先删除 WebServices attribute，再关闭 pool、调用 Connector/J checkedShutdown、逐个注销 owned driver。筛选同时要求“启动前不存在”且 driver classloader 等于当前 WebApp classloader；不会 indiscriminately 注销其他 WebApp/container 已有 driver。若 Connector/J 位于共享父 loader，driver 已存在且不进入 ownedDrivers，本应用不拥有也不清理它。

本轮未新增 Executor、Timer、Thread、static ThreadLocal 或 async request。已有 JdbcTransactionManager ThreadLocal 每次 finally remove；ConnectionPool 与 Connector/J 生命周期由 Listener 处理。

## 16. Standalone WAR deployment

WarDeploymentSmoke 的 launcher classpath 只有 Tomcat embed core，用来启动容器；应用 classes、Jackson 与 Connector/J 必须从实际 WAR 的 WEB-INF/classes/lib 加载。launcher 不含 Maven target/classes、test classes、JUnit、MysqlFixture、外部 Connector/J 或 Servlet API duplicate runtime。

测试预先初始化父 DriverManager，随后将同一 WAR 连续执行：startup → listener/pool/service initialization → 未认证 API 401 JSON → stop/cleanup → redeploy → 再次请求 → stop。两个 cycle 均通过；这既复现了最初 driver discovery 风险，也验证当前显式加载能在 redeploy 后再次工作。

Tomcat embed core 仅 test scope。最终 WAR 中不含 embed Tomcat 或 provided Servlet API。

## 17. web.xml review

web.xml 为 Jakarta Servlet 6.0、metadata-complete=true，集中注册一个 Listener、两个 Filter 和一个 `/api/*` Servlet；没有 annotation/XML 双套注册。

ApiExceptionFilter mapping 在 AuthenticationFilter 前；两者只处理 REQUEST。Servlet load-on-startup=1。Session timeout 30 分钟、HttpOnly true、tracking-mode COOKIE。SameSite/Secure 需要条件逻辑，因此由 Listener 设置。没有 ERROR/ASYNC recursion，也没有 javax.servlet 混用。

## 18. Routing, endpoints and status

ApiServlet 是一个明确的小型 switch + Project 子路径解析，不是 MVC framework。只实现本轮八个 method/path 组合：

| Method | Path | Success |
| --- | --- | ---: |
| POST | `/api/auth/login` | 200 |
| POST | `/api/auth/logout` | 204，无 body |
| GET | `/api/auth/me` | 200 |
| GET | `/api/projects` | 200 |
| POST | `/api/projects` | 201 + Location |
| GET | `/api/projects/{id}` | 200 |
| GET | `/api/projects/{id}/requirements` | 200 |
| POST | `/api/projects/{id}/requirements` | 201 |

不支持 method 返回 405 和 Allow；未知/trailing/extra route 返回 404；非正整数 ID 返回 400。使用 Servlet API 已解码 pathInfo，不自行 decode。没有所有错误都返回 200 的做法。

## 19. Service boundary and public DTOs

唯一新增读取能力是 ProjectService.list 和 ProjectDao.listAll。ADMIN 通过 Service 获得全部项目；普通 USER 先由 Service 验证 ACTIVE User，再仅根据 ACTIVE project_members 列出项目。Servlet 不直接调用 DAO。N+1 是小规模列表的性能问题，不是权限泄露；后续数据量/pagination需求明确后再增加合适查询。

Requirement GET/list/create 全部复用 Phase 3 Service；跨项目、membership、TESTER write、项目 ARCHIVED 和 REQ counter 仍由 Service/DB 处理。HTTP→Service→MySQL 测试证明 membership 失效后列表为空、单项目读取/创建 Requirement 为 403。

请求 DTO 仅包含允许写入字段：Login、CreateProject、CreateRequirement。响应 DTO 不含 password/passwordHash、DB config、session id、createdBy、lockVersion 或完整 Model。未来 update endpoint 可设计显式 expectedVersion/version，但本轮不提前暴露所有 persistence fields。

## 20. Logging and security

生产 Web/Auth 范围搜索未发现 printStackTrace、System.out/err、Authorization/Cookie/JSESSIONID/session.getId 或完整 request-body 日志。LoginRequest.toString 固定为 redacted。密码/hash 只在 AuthService/PasswordVerifier 内传递，不进入 response/log。

500 server log 只有异常 class 与 stack frames，不包含异常 message/cause；客户端固定 generic 500。Listener cleanup 日志同样为固定文本。没有请求 body、cookie 或 session id 日志。

## 21. Tests and final verification

新增 28 个 JUnit tests：PasswordVerifier 2、真实 Tomcat HTTP component 23、production web.xml + Listener + Service/DAO + 隔离 MySQL 3。另有不计入 JUnit 数字的独立 WAR 双次部署 smoke。

覆盖：valid login、bad/missing/disabled、Session rotation、旧 ID、logout invalidation、401 JSON、CSRF header omission、inactive membership、role/status change、伪造 actor/role、malformed/duplicate/trailing/wrong-type JSON、实际读取上限、UTF-8、exception mapping、500 no leakage、Project/Requirement 成功/400/403/404/409、standalone deploy/redeploy。

最终证据：

| Check | Result |
| --- | --- |
| `mvn test` | 82 PASS |
| `mvn -Pmysql-tests test` | 234 PASS；原 206 全部保留 |
| `mvn -Pmysql-tests clean package` | 234 PASS；WAR BUILD SUCCESS |
| HTTP component | 23 PASS |
| HTTP→Service→MySQL | 3 PASS |
| independent WAR | 2 deployment/shutdown cycles PASS |
| WAR content | PASS |
| `git diff --check` | PASS；仅 Windows LF/CRLF informational warnings |
| isolated database | MySQL 8.0.46 / 13307；最终 0 tables |
| cleanup | temporary MySQL shutdown；13307 no listener |
| development DB | 未连接/修改 `localhost:3306/qatrack` |

WAR runtime lib 只有 Jackson annotations/core/databind/jsr310 与 mysql-connector-j；不含 Servlet API、Tomcat embed、JUnit/Surefire、test classes/fixtures、local properties。schema、seed、ConnectionPool、JdbcTransactionManager 无 diff。

## 22. Public deployment blockers

真正开放公网前必须完成并实测：

1. 登录限流/抗爆破与来源地址的可信代理配置。
2. 一次性、可审计且不进 Git 的 ADMIN bootstrap/换密流程，并准备可登录账号。
3. HTTPS/TLS，`QATRACK_SESSION_SECURE=true`，可信反向代理与 Cookie 实测。
4. 完整 CSRF token 或严格 Origin/Host 校验方案；保留 JSON/custom-header gate，不因加 CORS 而削弱。
5. 若开放跨 origin UI，建立精确 CORS allowlist；禁止 wildcard + credentials。
6. 运维日志/告警与 Session 策略复核，包括 absolute timeout、全局撤销/换密后的 Session 处置需求。

这些不阻塞本机或受控内网 smoke deployment，也不阻塞 Round 1 commit；它们阻塞“已具备公网生产安全”的声明。

## 23. Round 2 recommendations

Final Review 后可进入 Round 2，但不应在本轮继续铺 API。Round 2 优先顺序建议：先解决演示账号 bootstrap 与 Web security deployment items，再按 UI 用例添加少量 API；任何 update API 明确 expectedVersion/version contract。避免在需求未明确时引入 JWT、OAuth、Spring Security、自制 MVC 或分布式 limiter。

## 24. Git status

`main` 与 `origin/main` 均为 `5c196c1`。最终文档加入后，working tree 包含 5 个 tracked modified 文件和 29 个 untracked 文件；staging area 为空。tracked `git diff --stat` 为 `5 files changed, 48 insertions(+), 2 deletions(-)`，Git 默认不统计 untracked 文件。

Modified：`pom.xml`、ProjectDao、JdbcProjectDao、ProjectService、DefaultProjectService。Untracked 为两份 Phase 4 文档，以及本轮 bootstrap/Auth/Web/DTO/web.xml/test 文件。没有 schema、seed、ConnectionPool、JdbcTransactionManager 变更。本轮没有 commit/push。

## 25. Final answers

- A. 批准 Phase 4 Round 1：是。
- B. commit 前必须修复：none。
- C. 可以 commit/push：是；建议先由 Final Review 人工浏览五个最高风险文件及本报告。
- D. 可以在真实 Tomcat 上进行非公开/受控 smoke deployment：是；需提供外部 DB 配置和临时准备的可登录账号。独立 WAR 目前已验证无账号场景的启动、401 JSON 与 redeploy。
- E. 公网前必须完成：登录限流、账号 bootstrap、HTTPS/Secure Cookie/代理验证、完整 CSRF/Origin 方案，以及相应安全运维检查。
- F. 可以进入 Phase 4 Round 2：Final Review 批准且 Round 1 commit/push 后可以；本轮未开始 Round 2。

优先 Review 文件：ApplicationListener、DefaultAuthService、PasswordVerifier、AuthenticationFilter、ApiExceptionFilter/JsonHttp；部署证据再看 WarDeploymentSmoke 与 AuthHttpIntegrationTest。
