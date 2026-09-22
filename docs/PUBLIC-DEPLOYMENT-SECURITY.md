# Public Deployment Security Gate

> Veriqra 更名说明：本文较早的测试数量、提交 ID、旧包名/上下文、WAR 文件名与校验值属于更名前历史证据；最新发布候选、兼容规则及 ROOT 部署请以 [VERIQRA-RENAME.md](VERIQRA-RENAME.md) 为准。数据库物理名称保持不变。

范围：Phase 4 Deployment Hardening，2026-09-21。仅本地 Web 安全与文档；不改变业务规则、schema、seed、DAO、连接池或事务管理器，不开始 Round 4。本轮未连接 ECS、未修改服务器、未 commit/push。

开始时本地 `main == origin/main == b920702`，working tree clean。此前 ECS 受控 smoke 成功是用户提供的部署记录，本轮没有重新核验云端状态。已有部署仍使用旧 WAR；本文件中的新策略必须随新 WAR 和服务器配置一起交付。

## 1. Threat model

目标是单台 Tomcat、单个 Nginx 边缘代理、同源 Cookie Session JSON API。防护重点为浏览器跨站写入、Host/forwarded header 伪造、登录猜测、账号状态枚举、会话固定和错误信息泄露。

不声称覆盖 XSS、被控制的同源脚本/浏览器、分布式凭据填充、大规模 DDoS、主机失陷或不可信本地进程。公网 Tomcat/MySQL 端口仍禁止开放。禁止新增宽泛 CORS 或把用户可控脚本托管在受信任应用同源。

## 2. Login rate limit

`web/security/LoginRateLimiter` 是每个 ApiServlet/webapp 实例独立的内存固定窗口限流器，位于 Web 层，AuthService 未修改。

| 项目 | 策略 |
| --- | --- |
| 窗口 | 从 bucket 第一次使用起 5 分钟，使用单调 `System.nanoTime` |
| IP + username | 同一窗口最多 5 次失败；第 6 次认证前返回 429 |
| IP 总量 | 同一窗口最多 30 次失败，避免更换用户名绕过 |
| 并发 | 在执行密码校验前预留配额，正在校验的请求也占配额 |
| 成功 | 清除此 IP+username 的失败数；不会抹除 IP 对其他账户的失败历史 |
| 非认证异常 | 释放预留，不把内部错误当作密码失败 |
| 拒绝响应 | 429 + 通用 JSON + 整数秒 `Retry-After`；不回显用户名、IP、计数或账户是否存在 |
| 过期 | 到窗口边界释放失败限制；不是永久账号锁 |
| 容量 | 最多 4096 个 bucket（IP bucket 与 pair bucket 合计） |
| 清理 | 每次 acquire 清理过期且无在途请求的 bucket，空 bucket 及时移除；无后台线程 |
| 容量饱和 | 不淘汰有效限制来放行新 key；新 key 返回 429/Retry-After: 1，客户端可重试，容量仍可能暂满 |

username 按 ASCII 认证契约处理，计数键忽略大小写和尾部空白，避免 MySQL 大小写不敏感用户名查询导致简单变体绕过。非法/超长名字不产生无限长度 key。限流器不保存 password、Cookie、Session ID 或登录 body。

同名用户从另一个 IP 不受 pair 限制，所以不是全局 username lock；同一 IP 的 NAT 用户可能共享 IP 限制，这是课程级策略的明确取舍。容量饱和时会牺牲部分登录可用性以保持内存有界。IP 来自可信容器的 `request.getRemoteAddr()`，不读取 `X-Forwarded-For`。

重启或 redeploy 会丢失计数；不支持跨节点共享。对公网持续/分布式攻击，应另行评估边缘总请求限流与监控，不在本轮加入 Redis 或分布式框架。

## 3. CSRF strategy

选择 **A：SameSite=Lax + 自定义请求头 + 严格固定 Origin/Host 校验**。当前 API 不需要跨域，采用该组合，无新增 CSRF token 系统。

这不是传统 synchronizer-token CSRF，而是当前 same-origin 部署模型下的组合防护。

`AuthenticationFilter` 在认证和路由之前调用 `SameOriginPolicy`。除 GET/HEAD/OPTIONS 外的所有方法必须同时满足：

1. 正好一个 `Origin`，解析为当前允许的 scheme/host/effective port。
2. 正好一个 `X-Veriqra-Request: 1`；一代兼容期也接受旧请求头，详见更名指南。两种头同时出现时都必须各自只有一个且值为 `1`。
3. JSON body 仍由共享 `JsonHttp` 要求 `application/json`，严格拒绝 malformed/unknown/duplicate/trailing JSON。

缺失 Origin、`Origin: null`、多 Origin、带路径/查询/fragment/userinfo 的 Origin、不同 scheme/host/port 均拒绝 403，不回退 Referer。curl/API smoke 也必须显式带合法 Origin，不设“非浏览器免检”例外。GET 不要求 Origin/header，仍检查 Host，不产生业务写入。

安全假设是浏览器限制脚本伪造 Origin，应用不开放 CORS、同源页面可信且使用 HTTPS。任意命令行客户端可以声明 Origin；Origin 不是身份认证，仍必须通过密码/Session/Service 权限。XSS 能以同源身份请求，因此此方案和 CSRF token 均不能代替 XSS 防护。

策略依据：[OWASP CSRF Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html)。此处为本项目在同源 JSON API 前提下的工程选择。

## 4. Origin / Host validation

新增环境变量：

```ini
VERIQRA_PUBLIC_ORIGIN=https://veriqra.xyz
VERIQRA_SESSION_SECURE=true
```

`veriqra.xyz` 是目标生产域名。Origin 不包含 context path、结尾 `/`、路径或通配符，仅允许一个 HTTPS Origin。非法配置启动失败；配置了 public origin 却没有显式 `Secure=true` 也启动失败。

所有 API 请求（含 GET）验证单一 Host，与容器提供的 scheme/serverName/serverPort 一致，再与固定 public origin 一致。不能靠同时伪造 Host 与 Origin 选出任意受信域名。

未设置 public origin 只用于本地/受控 HTTP：Host 必须为 localhost/127.0.0.1/IPv6 loopback，remoteAddr 也必须为 loopback。不是公网回退模式。经可信代理还原为公网 client IP 的请求在该模式下会被拒绝。

若启用 public origin 后做本地服务器 smoke，使用本机 Nginx HTTPS 入口并携带真实域名/SNI；直接以 `Host: 127.0.0.1:8080` 请求 Tomcat 会被拒绝，这是预期行为。旧部署脚本的缺 Origin 写请求同样不再兼容。

## 5. Cookie policy

`SessionCookiePolicy` 从 ApplicationListener 在启动阶段设置：Secure 使用明确环境开关，HttpOnly=true、SameSite=Lax、Path=实际 context（ROOT 为 `/`，命名部署为 `/veriqra`）、30 分钟闲置超时。

本地 HTTP 可以 `VERIQRA_SESSION_SECURE=false`，但不得同时配置 public origin。应用不因请求携带 `X-Forwarded-Proto: https` 而改变 Secure；生产必须显式 true。未设置 cookie Domain，使用 host-only cookie。测试同时验证 Secure/非 Secure、HttpOnly、SameSite 和 Path。

## 6. Trusted proxy model

唯一受信网络链路为 Internet → Nginx → Tomcat loopback。没有 CDN、多跳代理或任意私网代理。应用不解析任何 `Forwarded` / `X-Forwarded-*` / `X-Real-IP`，只使用容器校验后的属性。

Nginx 单层边缘必须**覆盖**而非追加客户端的转发信息：

```nginx
# 放在已经具备有效 TLS 的应用 server/location 中，server_name 使用 veriqra.xyz。
location / {
    # 安全验收完成前保留既有 allow/deny 限制。
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host "";
    proxy_set_header X-Forwarded-Port "";
    proxy_set_header Forwarded "";
}
```

Nginx 必须明确匹配最终 `server_name`，默认虚拟主机拒绝未知 Host；不能为客户端添加 Origin 或 X-Veriqra-Request，也不能加 CORS。单层边缘不使用 `$proxy_add_x_forwarded_for` 保留外部伪造链。原受控部署记录里的追加方式要在公网验收前调整。

Tomcat Connector 保持 `address="127.0.0.1"`。在选定 Engine/Host 范围配置 RemoteIpValve，限制 internalProxies 为实际环回 Nginx（以下只接受 IPv4/IPv4-mapped loopback）：

```xml
<Valve className="org.apache.catalina.valves.RemoteIpValve"
       internalProxies="127\.0\.0\.1|::ffff:127\.0\.0\.1|0:0:0:0:0:ffff:7f00:1"
       remoteIpHeader="X-Forwarded-For"
       protocolHeader="X-Forwarded-Proto"
       protocolHeaderHttpsValue="https" />
```

不配置客户端控制的 hostHeader；Host 由 Nginx 和固定 public origin 校验。HTTPS 默认端口为443；若将来用非标准端口，必须另行匹配 Nginx Host 与 Valve httpsServerPort 并重测。本例仅针对单层 loopback 和标准443。

应用信任 `getRemoteAddr/getScheme/getServerName/getServerPort` 的前提是上述代理清洗、Valve 和监听限制全部实测通过。只加 Valve 而不覆盖外部 header 不合格；不加 Valve 会导致整个站点共享 Nginx IP 配额及 HTTPS scheme 不匹配。主机本地进程属于可信主机边界。

配置依据：[Tomcat Remote IP Valve](https://tomcat.apache.org/tomcat-10.1-doc/config/valve.html#Remote_IP_Valve)、[Nginx proxy module](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)。本轮只编写部署要求，没有执行服务器配置。

## 7. Session policy

登录先使旧 Session 失效，再创建新 Session；logout invalidate；仅 Cookie tracking，URL Session ID 不被接受。30 分钟 inactivity timeout 已存在且保持；**没有 absolute timeout、全局 session registry 或全局 revoke**。

每次受保护请求重新检查 user ACTIVE，disabled 用户的当前 Session 被失效；项目角色/成员 ACTIVE 状态由 Service 实时查询。Session 只保存 actor ID，不缓存角色。密码更改本身不自动撤销所有已有 Session；应按受控运维程序清理会话。Tomcat 是否在 restart 持久化 Session 是容器配置事项，发布与凭据轮换时需明确选择并验证。

## 8. Authentication enumeration / CORS

不存在用户、错误密码、DISABLED 用户继续使用相同通用 401。PasswordVerifier 对不存在/非法 hash 有 dummy derivation；Final Review 已把 dummy 成本对齐当前生产 bootstrap 基线的 600000 次迭代，但不承诺网络级严格恒定时间。限流只依据请求 key 和失败，不依赖账户是否存在，429 不暴露账户状态。

保持 same-origin，不添加 Access-Control-Allow-Origin/Allow-Credentials。没有 `* + credentials`，OPTIONS 也不会授权跨域。未来跨域客户端需重新审查整个 CSRF、cookie、Origin 策略。

## 9. Error and logging

应用不日志 password、login body、Cookie/JSESSIONID、Authorization；LoginRequest.toString 保持 redacted。客户端只收到稳定错误码和通用消息，不包含 SQL、stacktrace、密码或内部 limiter bucket。429 只额外给出 Retry-After。

ApiExceptionFilter 保留已审查的异常类别/栈位置日志，不记录 exception message/cause，避免 JDBC/JSON 内容泄露。Nginx/Tomcat access logs 不配置 body、Cookie、Authorization 或 session id；运维同样不得公开真实配置/环境。

## 10. ADMIN bootstrap

沿用 Cloud handoff 的一次性受控 bootstrap procedure。用户提供的 ECS 记录表明 ADMIN 已存在且临时工具已删除；本轮不重建、不创建默认账号、不生成密码/hash、不在启动时自动建管理员。正式库不执行 seed.sql。

若确需新管理员或凭据轮换，再通过隐藏输入、PBKDF2、参数化 JDBC、受限管理连接和会话清理执行一次性运维，不增加公开 bootstrap API。

## 11. Nginx / Tomcat security headers

HTTPS、HTTP→HTTPS redirect、HSTS、frame policy 和 Referrer-Policy 放在 Nginx 应用 HTTPS server 中统一管理。建议 `X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`，HSTS 在有效 HTTPS 验收后配置，先不默认 includeSubDomains/preload。CSP 待前端明确后设计，不预先写死。

现有 API 已由 ApiExceptionFilter 返回 `X-Content-Type-Options: nosniff`，本轮保留，不在 Nginx 为同一响应重复叠加。未来静态前端可由 Nginx 对其 location 设置 nosniff。TLS证书、协议、安全补丁与实际 header 验收属于部署工作。

## 12. Public deployment checklist

- [ ] 确定稳定域名/DNS，配置有效 TLS、80→443 与未知 Host 拒绝。
- [ ] 保留 `/` 来源限制，备份当前 WAR/配置/数据库，核对新发布 commit 和 checksum。
- [ ] 配置 VERIQRA_PUBLIC_ORIGIN 为最终 HTTPS Origin，VERIQRA_SESSION_SECURE=true。
- [ ] 配置 Nginx 覆盖/清除 forwarded headers 与 Tomcat loopback-only RemoteIpValve。
- [ ] 实测正确 Host/Origin 可写，缺失/null/错误 Origin 返回403，错误 Host 被拒绝。
- [ ] 从外部发送伪造 X-Forwarded-For/Proto/Host，确认不能改变限流 client IP、scheme 或可信 Host。
- [ ] HTTPS 登录实测 Set-Cookie: Secure; HttpOnly; SameSite=Lax; Path=/，rotation/logout有效。
- [ ] 用专门受控账号验证失败阈值、429/Retry-After、窗口恢复；不要故意锁定唯一管理员的登录来源。
- [ ] 重新执行真实 API 核心链、持久化、restart/redeploy smoke，检查无意外500或秘密日志。
- [ ] 验证8080/3306仍仅环回、运行DB账号无DDL、不执行seed、不影响 status-api/Docker/ZZZSwitch。
- [ ] 验证可恢复的数据库备份、异地副本、恢复演练、基本告警与日志轮转。
- [ ] 完成 Public Deployment Final Review 和以上服务器验收后，才解除应用来源限制并做外部 smoke。

本清单为空表示服务器侧未在本轮执行，不能把本地测试通过当作公网已开放。

> Historical evidence below is preserved from the pre-rename security review. It describes the previous release, not the current Veriqra artifact; see VERIQRA-RENAME.md for current validation.

## 13. Remaining limitations and validation

代码侧不引入 CSRF token、Redis、JWT/OAuth 或新业务模块。已知限制：单实例限流重启丢失、共享NAT配额、全表容量饱和时临时拒绝新key、无absolute timeout/全局revoke、无分布式DDoS防护。上述对当前单机课程部署是明确边界；公网放行仍受第12节部署条件约束。

测试证据：LoginRateLimiterTest（窗口/清理/IP/容量/并发）、SameOriginPolicyTest（可信Host/Origin及伪造头）、SessionCookiePolicyTest（配置和timeout）、PublicSecurityWebTest（真实Tomcat 429/写入口门禁/Cookie），以及既有登录轮换、logout、disabled user、revoked membership和262项回归。

最终验证使用 JDK21 / IDEA bundled Maven / 独立 MySQL 8.0.46。Windows 验证进程的 TEMP/TMP 使用仓库外长路径，以避开本机 8.3 临时路径触发的 JDK AF_UNIX selector 环境故障；未修改 Windows PATH、系统配置或 Maven 依赖。

| Verification | Result |
| --- | --- |
| mvn test | 111 tests PASS，0 failures/errors/skips |
| mvn -Pmysql-tests test | 279 tests PASS，原262项和新增17项全部通过 |
| mvn -Pmysql-tests clean package | 279 tests PASS，BUILD SUCCESS |
| Standalone WAR | 两次独立部署/停止PASS，保护API返回401 JSON |
| WAR content | 三个新安全类存在；只有既有5个运行依赖，无测试类/本地配置/嵌入Tomcat |
| Test schema / shutdown / 13307 | qatrack_test_r1 为0表；临时实例正常关闭；13307无监听 |
| git diff --check | PASS |

WAR：`target/qatrack-0.1.0-SNAPSHOT.war`，SHA-256：

```text
F3B93D055E1EFBE1E3279CE42D3F20F666C5A59ACACE6941E742B69AE5CC382F
```

未连接或修改 `localhost:3306/qatrack`，没有访问ECS。

## 14. Findings and readiness

公开部署分级：

| 类别 | 当前结论 |
| --- | --- |
| A — Code-side blocker | Final Review 发现的 dummy PBKDF2 成本差异已修复；无未解决代码阻塞问题 |
| B — Deployment-side blocker | HTTPS证书、最终Origin、Secure Cookie、Nginx转发头覆盖、Tomcat可信代理、外部伪造头测试、最终安全smoke、防火墙/安全组验收仍须现场完成 |
| C — Future enhancement | absolute session timeout、global session revocation、distributed/persistent limiter、前端完成后的CSP收紧、requestId与增强可观测性 |

Final Review 将不存在用户的 dummy PBKDF2 成本从 210000 对齐到当前生产基线 600000，并补齐统一 Origin 门禁测试中的 Requirement 追踪、TestCase 更新和 TestPlan 更新/归档/关联写路径。随后重新执行 111 项 fast tests、279 项完整 MySQL tests、279 项 clean package 和两轮 standalone WAR 部署，全部通过。`web.xml`、AuthService、Phase 3业务规则、schema/seed、Maven依赖、ConnectionPool/JdbcTransactionManager均无改动。

- **CRITICAL：none。**
- **HIGH：无未解决代码问题；部署侧公网门禁仍未执行。** TLS、固定Origin、Secure Cookie、代理头清洗/Valve和外部验收完成前不得解除来源限制。
- **MEDIUM：Final Review 的 timing-hardening 差异已修复，无未解决代码 blocker。** 单节点限流的容量拒绝与共享NAT可用性取舍已明确；不声称分布式防护。
- **LOW：无absolute session timeout或全局revoke；保留为后续增强。** 当前已有30分钟闲置超时、实时disabled/membership检查。
- **INFORMATIONAL：** 重启丢失限流状态；认证响应通用但不同PBKDF2迭代配置不保证严格恒定时间；代理配置仍需在真实TLS链路实测。原有deprecated API/Tomcat可选反射泄漏检查警告未扩大或绕过。

**A. 正式公网开放的代码侧安全条件？** 在本文单机同源HTTPS部署假设下，已完成本轮代码要求，可提交Final Review；不代表真实部署已经开放。

**B. code blocker？** 本轮验证未发现。

**C. deployment-only blocker？** 有，第12节服务器安全配置和实际验收尚未执行。

**D. 登录限流？** 5分钟固定窗口，pair 5次/IP 30次失败，含并发预留，4096 bucket上限，429/Retry-After，成功清pair、保留IP失败。

**E. CSRF策略？** SameSite=Lax、自定义头、必须Origin、固定HTTPS Origin/Host校验、无CORS；缺Origin写请求拒绝，无新增token系统。

**F. Trusted proxy责任？** Nginx清洗头，Tomcat仅信任实际loopback代理并还原scheme/clientIP，应用只用容器属性并校验固定Host/Origin。

**G. HTTPS/Secure Cookie服务器设置？** 有效TLS+正确server_name+80转443；QATRACK_PUBLIC_ORIGIN为最终HTTPS Origin；QATRACK_SESSION_SECURE=true；匹配RemoteIpValve与loopback监听，并实际验收cookie。

**H. Public Deployment Final Review？** 已通过，结论见 `PUBLIC-DEPLOYMENT-SECURITY-FINAL-REVIEW.md`。本轮没有commit/push、连接服务器或开始Round 4。
