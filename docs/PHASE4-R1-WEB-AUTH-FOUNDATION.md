# Phase 4 Round 1 — Authentication + Servlet Foundation + JSON Error Model

日期：2026-09-20。开始时 `main`、`origin/main` 和 `git ls-remote origin refs/heads/main` 均为 `5c196c1 feat(service): complete core business service layer`；working tree clean。重新读取实际仓库后实施。本轮不 commit/push。

## 1. HTTP architecture

HTTP → ApiExceptionFilter → AuthenticationFilter → ApiServlet → Service interface → DAO interface → JDBC / MySQL。

`bootstrap.ApplicationListener` 是 composition root，负责加载外部 DatabaseConfig、显式加载 Connector/J、创建/销毁 ConnectionPool、注入 Service。停止时关闭池，并注销本应用新注册的 JDBC driver、关闭其 cleanup thread；共享父 classloader 已有驱动不由本应用关闭。Servlet/Filter 不创建事务、不访问 DAO、不拼 SQL。原有 Service 的跨项目、权限、Counter、状态机规则不变。

## 2. Servlet structure

只有一个小型 `ApiServlet`，明确匹配八个 endpoint/method 组合。两个 Filter 与 Listener 全部由 `src/main/webapp/WEB-INF/web.xml` 注册，使用 Jakarta Servlet 6.0，`metadata-complete=true`，没有注解与 XML 双重映射。错误 Filter 在认证 Filter 外层；目前只有同步 REQUEST dispatch。

Servlet API 6.0.0 为 provided，运行时由 Tomcat 10.1 提供，不能将 Servlet API JAR 打入 WAR。没有 BaseServlet 继承树或自制路由框架。

## 3. JSON mapper

`JsonMapperProvider` 内部只有一个配置完成后不再修改的 ObjectMapper，对外只给 immutable reader/writer。

最小生产依赖为 Jackson databind 2.21.6 和 jsr310 2.21.6；后者用于 Project/Requirement DTO 的 LocalDateTime。core/annotations 为传递依赖。Jackson 2.21 为仍维护的 LTS 系列：[官方发布记录](https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.21)。

- 输入必须 application/json，按严格 UTF-8 解码；非法 UTF-8、非法 JSON、unknown field、duplicate field、trailing token 拒绝。
- 不接受数字/布尔自动变成字符串，不接受数字枚举、单值数组等宽松转换。
- JSON body 最大 64 KiB、深度 32、数字文本 32 字符。
- Java Time 输出 ISO-8601 LocalDateTime 字符串，例如 `2026-09-20T10:00:00.123456`；沿用数据库 UTC 约定，类型本身无 offset，不伪造 Z。
- Response 序列化成 bytes 成功后才开始写 body，避免序列化错误已提交一半成功响应。
- 错误响应不包含 Jackson 原始异常文本（可能回显密码）。

## 4. Error model and exception mapping

格式为 `{"error":{"code":"...","message":"..."}}`，message 使用固定安全文本，不返回 SQLException、参数、堆栈或原始请求。

| Failure | HTTP | Code |
| --- | --- | --- |
| ValidationException | 400 | VALIDATION |
| malformed/unknown/wrong-type JSON | 400 | INVALID_JSON |
| invalid path ID | 400 | INVALID_ID |
| AuthenticationException / missing session | 401 | UNAUTHENTICATED |
| ForbiddenException | 403 | FORBIDDEN |
| missing unsafe-request header | 403 | REQUEST_HEADER_REQUIRED |
| NotFoundException / unknown route | 404 | NOT_FOUND |
| wrong method | 405 + Allow | METHOD_NOT_ALLOWED |
| ConflictException | 409 | CONFLICT |
| oversized JSON | 413 | PAYLOAD_TOO_LARGE |
| non-JSON content type | 415 | UNSUPPORTED_MEDIA_TYPE |
| DataAccessException / unexpected Throwable | 500 | INTERNAL_ERROR |

`ApiExceptionFilter` 统一处理，Servlet 无重复 try/catch。500 使用 ServletContext.log 记录异常类型与栈帧；故意不记录 message/cause，以免 JDBC/JSON 异常带入敏感数据。VM 致命错误尝试处理后仍重新抛出。已经 committed 的响应不能重新写 JSON；网络断开不保证能够交付响应。

所有 API 响应带 Cache-Control: no-store 和 X-Content-Type-Options: nosniff。JSON body 为 application/json;charset=UTF-8；204 无 body。

## 5. AuthService and password verification

新增 `AuthService` / `DefaultAuthService`：

- authenticate(username, password)：读取用户、验证 hash、检查 ACTIVE，并在验证后用新的 Service 事务复核 status/hash，防止密码计算期间禁用或换密码后仍通过。
- current(actorUserId)：每次读取当前 User，DISABLED/不存在均认证失败。
- 返回 `AuthenticatedUser` 安全投影，不返回完整 User 或 passwordHash。
- PBKDF2 计算时不持有 JDBC lease；事务仍由已有 ServiceTransaction 管理。

冻结 users 已有 password_hash VARCHAR(255)。seed 格式为 `pbkdf2_sha256$iterations$hexSalt$hexDigest`，210000 次，原密码已丢弃，不是可用的开发登录账户。本轮没有修改 seed、重置账号或接触开发库。部署实际登录前需要由管理员通过独立受控流程准备已知密码的兼容 hash；没有注册/密码重置/用户管理 API。

`PasswordVerifier` 使用 JDK PBKDF2WithHmacSHA256、PBEKeySpec、MessageDigest.isEqual，不新增密码库、不存明文、不使用 MD5/SHA-1。参考 [JDK 21 标准算法](https://docs.oracle.com/en/java/javase/21/docs/specs/security/standard-names.html)。

兼容已有 210000 次 hash；接受 210000..1000000 次，12..64-byte salt 与 32-byte digest，拒绝其他格式/越界参数；未知账号/非法 hash 走 dummy derivation 并失败。密码最多 1024 字符，不 trim；临时 char[]、PBEKeySpec、派生 bytes 用后清理，但 Servlet/Jackson 输入 String 无法保证原地擦除。LoginRequest.toString() 完全隐藏字段。

隔离测试创建随机 salt、600000 次的测试 hash。此轮只验证，不实现注册、hash 升级或自动 rehash。dummy 不是严格的恒定响应时间保证，特别是存量 hash 的成本不同。

## 6. Session identity and authentication filter

Session 只保存 Long actorUserId，30 分钟 idle timeout。登录成功销毁旧 Session 并创建新 Session，旧 ID 和旧属性不保留；失败登录不创建新的登录态。logout invalidate，旧 cookie 随后返回 401。

`/api/*` 默认要求 Session，只有精确 `/api/auth/login` 放行，且该路由只支持 POST。Filter 经 AuthService.current 检查当前用户；认证失效时销毁 Session。Service 仍重新读取当前角色/member 状态，Filter 不能替代业务授权。

`/auth/me` 查询当前数据库信息，Session 不保存永久角色、状态或 membership。请求 DTO 没有 actorUserId/systemRole/projectRole/isAdmin 字段，伪造字段直接 400；Servlet 调 Service 的 actor 只来自 Session。

## 7. Security boundaries

- HttpOnly：web.xml cookie-config。
- SameSite=Lax：Servlet 6 SessionCookieConfig attribute；已在真实 Tomcat 响应验证。Tomcat 也提供部署级 [CookieProcessor SameSite 配置](https://tomcat.apache.org/tomcat-10.1-doc/config/cookie-processor.html)，部署配置不要与应用设置矛盾。
- HTTPS 部署设置 `QATRACK_SESSION_SECURE=true`；只允许 true/false。开发 HTTP 可为 false/未设。反向代理应正确配置可信代理与 secure scheme，不接受未经信任的转发头作为证明。
- 只允许 COOKIE tracking，不使用 URL Session ID；真实 HTTP 测试验证仅 URL ID 无法登录。
- 所有非 GET/HEAD/OPTIONS 请求（包含 login/logout）要求 `X-QATrack-Request: 1`。配合无 CORS 放行，浏览器跨源脚本无法通过预检发送该请求头；写入 JSON DTO 的路由另外要求 application/json。
- 本轮同源部署，没有 Access-Control-Allow-Origin/credentials 放行，没有把 Session 当作自动 CSRF 防护。
- 未实现完整同步 CSRF token。未来如果增加 CORS、表单入口或放行跨源请求，必须重新设计 CSRF/Origin allowlist，不能移除当前请求头检查。
- 请求头不是身份凭证，所有写操作仍需 Session 与业务权限；curl 等非浏览器客户端可直接设置它。
- 不记录密码/hash/session id/完整请求体；异常安全日志不记录 cause message。
- 未实现登录速率限制、账号锁定、密码修改、会话全局撤销或 session absolute timeout。公开互联网部署前需补限流与 HTTPS 运维验证。

## 8. Endpoints and HTTP status

| Method | Path | Success | Behavior |
| --- | --- | --- | --- |
| POST | /api/auth/login | 200 | LoginRequest → safe UserResponse，创建新 Session |
| POST | /api/auth/logout | 204 | invalidate Session，无 body |
| GET | /api/auth/me | 200 | 当前数据库 UserResponse |
| GET | /api/projects | 200 | ADMIN 全部；USER 仅 ACTIVE membership 的项目 |
| POST | /api/projects | 201 | ADMIN create + 原 Service 原子创建 counters；Location 指向已实现的项目 GET |
| GET | /api/projects/{projectId} | 200 | 原 Service project read 权限 |
| GET | /api/projects/{projectId}/requirements | 200 | 原 RequirementService.listByProject |
| POST | /api/projects/{projectId}/requirements | 201 | 原 RequirementService.create 与 REQ Counter |

没有 /api/v1、JWT、OAuth、前端或其它业务 API。项目归档后可读语义沿用已有 Service。

## 9. DTOs and Service boundary

请求：LoginRequest、CreateProjectRequest、CreateRequirementRequest。
响应：UserResponse、ProjectResponse、RequirementResponse、ApiError。

响应不暴露 passwordHash、lockVersion、createdBy 等内部字段；返回用于展示的 identity/key、业务字段、状态与时间。无 update endpoint；下轮若增加更新，必须将 expectedVersion 作为客户端预期版本处理，同时提供相应只读版本信息，不能让客户端指定数据库新版本。

仅扩展 ProjectService.list 与 ProjectDao.listAll/JdbcProjectDao.listAll。USER list 重用已有 active membership query 和 findById，权限选择在 Service；DAO 不添加业务授权。小型课程项目暂不分页，USER 列表有 N+1 查询成本，后续按真实数据量再优化。已有 create/get/update/archive 规则未改。

## 10. Tests

新增 28 项：

- PasswordVerifierTest：2 项，JDK PBKDF2 600000/Unicode 正确密码、错误密码、格式/长度/迭代边界拒绝。
- WebFoundationTest：23 项，用真实 Tomcat 10.1.60 + Service 测试替身，验证 Session 轮换/旧 ID 拒绝/退出、URL ID 拒绝、实时认证、csrf header、全部异常映射、500 不泄露、畸形/错误类型/未知/重复 JSON、身份伪造、大小/media/method/path、UTF-8 与 Java Time DTO。
- AuthHttpIntegrationTest：3 项，实际 production web.xml + ApplicationListener + Servlet + 真实 Service/DAO + 隔离 MySQL。验证 PBKDF2 登录/错误密码/不存在/禁用、项目/需求创建与验证、权限403/冲突409/不存在404、membership 撤销、ADMIN降权、用户禁用后旧 Session 不缓存权限。

Tomcat embed core 仅 test scope；API-only 测试关闭内嵌 Tomcat 的默认 JSP servlet（未引入 Jasper），仍加载正式 web.xml 的全部映射与 Listener。测试服务仅监听 loopback 临时端口，结束 stop/destroy。没有 mock Servlet 的业务规则。

另有 `WarDeploymentSmoke` 在 package 后通过 Java source launcher 单独执行（不计入 JUnit 数字）：启动 classpath 仅 Tomcat embed core，预先初始化 DriverManager，再使用实际 WAR 连续部署/停止两次，检查保护接口 401 JSON。该测试发现并验证修复了父 classloader 提前初始化 JDBC 后，WAR 内驱动未自动发现的启动问题。它严格限定 13307/qatrack_test_* 和非 root 配置，拒绝应用环境覆盖。

沿用 MysqlFixture 的严格 test URL/非root/空库/版本校验与 FK 清理；新测试不改变 fixture。Web 集成测试额外拒绝所有应用 QATRACK_DB_* 环境覆盖，防止覆盖隔离外部配置；临时 properties 含测试凭据，仅在 JUnit 临时目录，用后清理。

## 11. Deployment

1. Java 21、Tomcat 10.1（Servlet 6）、MySQL 8.0.46。
2. 外部配置沿用 classpath defaults < external properties < QATRACK_DB_*。设置 QATRACK_DB_CONFIG 或 -Dqatrack.db.config 指向仓库外配置，不依赖 Tomcat 工作目录。
3. 部署到指定 context，路径例：/qatrack/api/auth/login。配置文件/凭据不打包到 WAR。
4. HTTPS 设置 QATRACK_SESSION_SECURE=true；配置可信反向代理与 TLS。
5. 浏览器同源请求 POST 时带 Content-Type: application/json 和 X-QATrack-Request: 1；后续由浏览器带 HttpOnly cookie。
6. 必须先准备可登录账号。冻结 seed 不提供已知密码，本轮未做开发账号密码重置。
7. Listener 启动失败则拒绝部署；正常停止关闭自定义池。Servlet/Jackson runtime 不需要 test JAR。

独立 WAR smoke（先完成 package，并安全启动已有隔离测试 MySQL）：

```text
java -cp <M2_REPO>/org/apache/tomcat/embed/tomcat-embed-core/10.1.60/tomcat-embed-core-10.1.60.jar src/test/java/io/github/lz007001cn/qatrack/web/WarDeploymentSmoke.java target/qatrack-0.1.0-SNAPSHOT.war config/database-test.local.properties
```

该启动 classpath 故意不含应用 classes、Connector/J、Jackson 或 JUnit；它们必须从 WAR 自身加载。下述本机 JDK socket 参数如需要应另加到 java 命令。

本机 Microsoft JDK 21 的 Windows AF_UNIX selector wakeup 在默认临时路径抛 Invalid argument: connect。验证命令仅给测试 JVM 传 `-Djdk.net.unixdomain.tmpdir=<NONEXISTENT_SOCKET_DIR>`，使该 JDK 自动回退 loopback TCP。未修改 Windows PATH、JDK、生产源码或项目配置以绕过此问题。其他环境无需该本机参数。

## 12. Final verification

| Check | Final result |
| --- | --- |
| mvn test | 82 tests，0 failures/errors/skips，BUILD SUCCESS |
| mvn -Pmysql-tests test | 234 tests，0 failures/errors/skips，BUILD SUCCESS |
| mvn -Pmysql-tests clean package | 234 tests，0 failures/errors/skips，WAR BUILD SUCCESS |
| independent WAR smoke | 两次独立部署/停止均通过，401 JSON 正确；启动 classpath 仅 Tomcat |
| git diff --check | PASS；仅 Windows LF/CRLF 提示 |
| isolated MySQL | 8.0.46，13307，qatrack_test_r1 最终 0 tables |
| shutdown | mysqladmin 正常关闭，13307 no listener |
| development database | 未连接/修改 localhost:3306/qatrack；MySQL80 服务仍 Running/Automatic |
| frozen files | schema、seed、ConnectionPool、JdbcTransactionManager 均无 diff |

原有 206 项全部保留，本轮新增 28 项；独立 WAR smoke 不计入 JUnit 数量。最终三条 Maven 验证均在 JDBC 启动修复后重跑，并使用上节记录的本机测试 JVM socket 参数。

WAR：`target/qatrack-0.1.0-SNAPSHOT.war`。WEB-INF/lib 只有：

- jackson-annotations-2.21.jar
- jackson-core-2.21.6.jar
- jackson-databind-2.21.6.jar
- jackson-datatype-jsr310-2.21.6.jar
- mysql-connector-j-9.7.0.jar

包含 production classes 与 WEB-INF/web.xml；不含 Servlet API、Tomcat embed、JUnit/Surefire、test classes/fixtures、WarDeploymentSmoke 或本地 properties。生产的 JUnitXmlParser 是已有导入功能，不是 JUnit 测试框架。

Git：5 个 tracked 文件修改（pom、ProjectDao/JdbcProjectDao、ProjectService/DefaultProjectService），28 个新增文件未跟踪；全部未暂存。`git diff --stat` 为 5 files changed, 48 insertions(+), 2 deletions(-)，该统计不包含新增文件。本轮无 commit/push，main 仍为 5c196c1。

## 13. Findings and remaining boundaries

- CRITICAL：none。
- HIGH（已修复）：独立 WAR 启动时 DriverManager 已被父 classloader 初始化，导致 Connector/J 仅在 WEB-INF/lib 时未被发现；Listener 显式加载并管理本应用驱动生命周期，独立 WAR 双次部署 smoke 验证通过。无未关闭 HIGH。
- MEDIUM（公开部署前）：尚无登录限流/抗爆破与完整 CSRF token；当前仅同源、自定义请求头、严格 JSON、Session cookie 策略。限制已明确，不宣称生产安全体系完成。
- LOW：USER 项目列表 N+1 与无分页；日志没有 requestId；更新 endpoint/expectedVersion、注册/密码重置、用户管理均未实现。

本轮批准边界为 Authentication + Servlet/JSON 基础与 Project/Requirement 最小 API。Round 2 再讨论其它业务 API、update version contract、账户准备及安全加强；本轮不开始这些功能。

## 14. Priority review

1. service/DefaultAuthService.java：密码验证与状态/hash二次确认。
2. service/auth/PasswordVerifier.java：冻结hash兼容、参数边界、dummy与constant-time比较。
3. web/AuthenticationFilter.java：Session入口与自定义请求头/CORS边界。
4. web/JsonHttp.java：严格JSON、大小限制和错误不回显。
5. bootstrap/ApplicationListener.java：配置优先级、池生命周期、Cookie secure设置。

附加证据优先看 AuthHttpIntegrationTest 与 WebFoundationTest。
