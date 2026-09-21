# Cloud Deployment Handoff / 云端部署交接

日期：2026-09-21。范围：Phase 4 Round 3 完成后的首次云端部署准备，仍等待 Final Review。本文件为方案，未连接或修改任何服务器，未部署、commit/push 或进入 Round 4。

已核对 pom.xml、实际 WAR、DatabaseConfig、ConnectionPool、ApplicationListener、PasswordVerifier、DefaultAuthService、SessionIdentity、web.xml、DAO SQL、schema.sql/seed.sql 与 Round 3 验证结果。下文区分“代码事实”与“待在服务器确认的部署建议”。

## 1. Architecture / Required software

目标：Internet → Nginx :80/:443 → Tomcat 127.0.0.1:8080 → 本机 MySQL :3306。

- Java 21；WAR 编译 release=21。
- Tomcat 10.1，Jakarta Servlet 6.0（provided），不能使用 javax.servlet 的 Tomcat 9。
- MySQL 已验证版本 **8.0.46**，不是 MariaDB，也不保证任意“MySQL 8”版本等价。首次部署优先匹配，其他补丁版本需另行验证。
- Ubuntu/Nginx 的实际发行版、安装方式、补丁及路径尚未确认。
- 服务器不需要 Maven 来运行 WAR。没有 Spring/JPA/MyBatis，也没有前端首页。
- 运行时 WAR 内含 Connector/J 9.7.0、Jackson annotations 2.21、core/databind/jsr310 2.21.6。Servlet API、JUnit、embedded Tomcat 与测试类不打包。不要再向 Tomcat lib 复制第二份 Connector/J。

## 2. WAR artifact / Context path

实际构建文件：`<PROJECT_ROOT>/target/qatrack-0.1.0-SNAPSHOT.war`，5,132,272 bytes。

SHA-256：
```text
1F639FFF28B5271A936D6EEAA8AB61A6D196259AEB997B615407F540A28B908D
```

pom 未设置 finalName，artifactId=qatrack、version=0.1.0-SNAPSHOT、packaging=war；本校验值对应当前尚未提交的 Round 3 working tree，不应把基线 commit 当作该 WAR 的全部源码。Final Review 后若重建，重新记录 checksum 与发布源码版本。

Tomcat 常规 appBase 自动部署、无外部 Context 覆盖时：
- 原名 → context `/qatrack-0.1.0-SNAPSHOT`。
- 推荐以 `qatrack.war` 部署 → context `/qatrack`，API 为 `/qatrack/api/...`。
- `ROOT.war` → 空 context，API 为 `/api/...`。须确认原 ROOT 应用/Context 不冲突，同步代理规则、客户端 URL 和 Cookie Path；不要并行残留旧 qatrack 部署导致两份实例/连接池。应用 Location 使用 request.getContextPath()，不硬编码 /qatrack。ROOT 路径尚未独立实测。

Tomcat 的文件名/context 规则见 [官方 Context 文档](https://tomcat.apache.org/tomcat-10.1-doc/config/context.html)。现有独立 WAR smoke 实测 context=/qatrack，两次 deploy/stop 成功；并未证明真实云端配置已就绪。

## 3. Database config keys / External config mechanism

配置值优先级：`classpath /database.properties < 外部 UTF-8 properties < 对应环境变量`。

| properties key | 实际环境变量 | 示例/当前默认 | 必须性 | 敏感性 |
| --- | --- | --- | --- | --- |
| jdbcUrl | QATRACK_DB_JDBC_URL | jdbc:mysql://127.0.0.1:3306/qatrack；默认 localhost | 有默认，部署须确认 | 地址信息；禁止嵌入凭据 |
| username | QATRACK_DB_USERNAME | qatrack_app（默认） | 非空，有默认 | 账号信息 |
| password | QATRACK_DB_PASSWORD | 现场生成的独立随机口令，不在本文提供值 | **必须外部提供**；classpath 无默认 | **秘密** |
| initialPoolSize | QATRACK_DB_INITIAL_POOL_SIZE | 默认 2，建议 2 | 有默认；>=0 且 <=max | 否 |
| maxPoolSize | QATRACK_DB_MAX_POOL_SIZE | 默认 8，首次小机建议 4 | 有默认；>=1 | 否 |
| acquireTimeout | QATRACK_DB_ACQUIRE_TIMEOUT | 默认/建议 3000，单位 ms | 有默认；1..300000 整数 | 否 |

空环境变量也会覆盖文件，不会被当作“未设置”；不要导出空值。空 username/URL/数值无效；password 代码只拒绝 null，虽接受空串，部署禁止空密码。数值不带单位字符串。

外部文件定位顺序：
1. 环境变量 `QATRACK_DB_CONFIG`；
2. 未设置时用 JVM system property `-Dqatrack.db.config=/opt/qatrack/config/database.properties`；
3. 均未设置时尝试相对工作目录 `config/database.local.properties`。

显式指定文件不存在会启动失败；默认相对文件不存在可跳过，但仍需外部密码。不支持自动扫描 /opt，也不存在自创的 QATRACK_DB_HOST/PORT 或 Spring 配置项。

推荐部署路径 **/opt/qatrack/config/database.properties**，不在 webapps/WAR/Git 中；目录 root 管理，文件 root 所有、Tomcat 专属组可读，例如目录 0750、文件 0640。真实 Tomcat 用户/组和 systemd sandbox 路径权限需现场核对。properties 中反斜杠等需按 Java properties 规则转义，避免把环境文件语法当 properties 语法。

建议以 QATRACK_DB_CONFIG 指向文件。只在 systemd 环境传路径，真实密码放受限文件，避免命令行/JVM参数/公开日志携带密码。不要运行并公开包含凭据的完整环境、进程或配置输出。

### Timeout 与其他真实行为

- acquireTimeout 是**等待池容量**超时，不是 SQL 执行或 HTTP 总超时。
- ConnectionPool 物理连接 properties 固定 connectTimeout=3000 ms、socketTimeout=10000 ms；健康检查 isValid(1)。不存在对应独立 QATRACK 环境变量，不承诺 URL 与 Properties 同名参数优先级。
- 每次物理连接初始化/归还恢复 session time_zone='+00:00' 与固定严格 sql_mode。
- 池保留连接原有 transaction isolation，**不强制设置 REPEATABLE-READ**。服务器需实际核对该隔离级别，详情一致快照依赖它。
- JDBC URL 可传 Connector/J 参数，但这不是项目新增配置项。TLS、MySQL 认证插件、证书信任需现场匹配；不要为了连接成功默认加 allowPublicKeyRetrieval=true 或禁用 TLS。本地环回仍需验证正确认证配置。
- Session 开关实际存在：`QATRACK_SESSION_SECURE=true|false`，严格小写；缺省 false，非法值导致启动失败。它不是 database.properties key。

## 4. Database initialization / Application user permissions

首次空库应由**初始化管理员**创建 qatrack，CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci；显式选择目标空库后执行 database/schema.sql。

schema.sql 不 CREATE DATABASE、不 USE、不 DROP、不 IF NOT EXISTS；需要空库，创建 19 张 InnoDB 表。失败后不要在部分安装的生产库上反复重跑；先调查并由管理员决定恢复全新空库。应用不会自动创建、迁移或检查全部 schema。

**正式首次部署不执行 seed.sql。** seed 是带固定 ID、演示项目/用户/执行/缺陷关联的验收数据，密码 hash 来自已丢弃随机输入，没有已知可登录密码。不用于 ADMIN bootstrap，不适合混入生产业务库。若将来需要演示数据，只进入独立演示库并另行授权。

运行账号建议 qatrack_app，仅匹配本机连接来源，不使用 root/%；MySQL 实际 localhost/127.0.0.1 账号匹配须现场 SELECT CURRENT_USER() 验证。

检查 DAO 的权限种类结论：**SELECT、INSERT、UPDATE、DELETE；不需要 DDL 或其他管理权限**。DELETE 确实用于替换当前 test_steps、移除 test_plan_cases 与纠正 test_attempt_defects，不可因系统有软删除就省略。

可进一步按当前全部 DAO 能力缩小授权：
- SELECT、INSERT：19 张应用表。
- UPDATE：users、projects、project_members、project_counters、requirements、test_cases、test_steps、test_case_requirements、test_plans、test_runs、defects、test_automation_mappings。
- DELETE：仅 test_steps、test_plan_cases、test_attempt_defects。
- 不授予 CREATE/ALTER/DROP、FILE、PROCESS、SUPER、GRANT OPTION、CREATE USER；无存储程序，因此不需要 EXECUTE。事务、普通 session 设置和现有 locking reads 不需额外管理员权限。

SELECT/INSERT/UPDATE/DELETE ON qatrack.* 是较简单但**不是最小表级授权**的备选；优先使用上面表级清单。当前 Web 不开放 Automation/Import，给其表权限只是覆盖既有完整 DAO 能力，不意味着新增 API。初始化与运行账号分离；生产环境不运行 MysqlFixture/constraint-tests 的写入用例。

## 5. ADMIN bootstrap requirement

当前仓库没有生产 bootstrap 命令、注册/改密码 API 或密码管理 UI。第一次真实登录前必须单独准备一个 ACTIVE ADMIN；不能依靠 seed。

最小一次性方案（部署时另行执行，本轮没有生成工具或账号）：
1. 在受控管理终端使用一次性 Java 21 小工具，通过 Console.readPassword 隐藏输入两次强随机密码；不要把密码作为命令参数、环境变量、源码或 shell 历史。用户自行保管密码，不要求发给助手。
2. SecureRandom 生成 **16 bytes salt**；PBEKeySpec(passwordChars,salt,600000,256)，SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") 派生 **32 bytes**。
3. 格式为 `pbkdf2_sha256$600000$<32个十六进制salt字符>$<64个十六进制digest字符>`。用 HexFormat 编码；保留字面量 $，不要通过会展开 $ 的 shell 拼接 hash。
4. 在内存中使用现有 PasswordVerifier 对生成 hash 验证；随后用初始化账号的 JDBC PreparedStatement，在一次事务中插入 users(username,display_name,password_hash,system_role,status)，值包含 ADMIN/ACTIVE。不指定 id/时间/version，采用 schema 默认值；检查用户名与已有 ADMIN，存在时停止人工核对，不自动覆盖或 upsert。
5. 不把明文或 hash 打印到 stdout/日志；不生成公开 INSERT 脚本。管理连接凭据同样隐藏输入或来自受限文件。避免 SQL trace/general log 记录绑定值，并遵守服务器既有审计策略；hash 也按敏感资料处理。提交后清理临时文件和内存 char[]，只记录不含秘密的成功/用户 ID。
6. 通过受控入口登录验证，再退出。若使用一次性临时密码或操作有泄露疑虑，应替换为独立强密码；**当前没有改密码功能**，只能以同样受控方式重新派生 hash、管理员参数化 UPDATE，并更新时间/lock_version。现有 Session 不绑定 password hash，密码变化不会自动使已登录 Session 全部失效，需显式失效/清理会话后验证重新登录。

兼容范围由实际 PasswordVerifier 决定：iterations 210000..1000000；salt 12..64 bytes；digest 32 bytes；密码 Java String 长度<=1024，AuthService 拒绝空密码。建议直接用最终强密码 bootstrap，避免制造必须立即更换但没有 UI 的公开默认账号。

## 6. Tomcat startup / JVM / systemd

ApplicationListener 显式加载 com.mysql.cj.jdbc.Driver，读取配置、建池、注入 Services；停止时关闭物理连接、停止本应用 Connector/J cleanup thread 并注销其驱动。启动日志没有专用“Listener success”标记，应结合 context available、无初始化异常、后续数据库 API 成功确认。

启动外部条件：Java21/正确Tomcat；配置可读；MySQL可达、账号/凭据与 schema 正确；足够连接数/内存/可写Tomcat日志和工作目录；QATRACK_SESSION_SECURE 合法。initialPoolSize=2 会在启动时实际建连接；设0会延迟检测数据库错误，不建议用于首次验证。启动成功不等于19张表完整或 DML 权限齐全。

服务器约3.5GiB RAM、2GiB Swap，另有 MySQL/Nginx/Docker：建议先用 **-Xms128m -Xmx512m**，连接池2/4，观察实际 RSS/GC/可用内存后调整。512m仅堆上限，不是进程总内存；swap 不作为容量保证。不要未经检查就修改其他服务的内存配置。

systemd 建议：先检查真实 unit 的 ExecStart、环境文件与 CATALINA_BASE，再为实际 unit 创建 /etc/systemd/system/<unit>.service.d/qatrack.conf，例如：
```ini
[Service]
Environment="QATRACK_DB_CONFIG=/opt/qatrack/config/database.properties"
Environment="QATRACK_SESSION_SECURE=true"
```
JVM 参数追加到该 unit **实际消费的** CATALINA_OPTS/JAVA_OPTS/包装器配置中，保留已有参数；不是所有 Ubuntu Tomcat unit 都读取 CATALINA_OPTS 或 setenv.sh。若用标准 catalina.sh，可通过 CATALINA_BASE/bin/setenv.sh 追加 CATALINA_OPTS；不要在未检查 ExecStart 前声称某路径必然有效。受控纯HTTP隧道测试才可暂设 Secure=false；HTTPS阶段必须true并实测。

## 7. Nginx / HTTPS / trusted proxy

目前应用没有解析 X-Forwarded-Proto 的 Filter，也未在仓库配置 RemoteIpValve。**不能认定代理后的 HTTPS 已自动识别。** QATRACK_SESSION_SECURE=true 可以强制 Secure Cookie，但并不独立修正 request.isSecure()/scheme/client IP。可信代理配置与实测是公网部署 blocker。

建议在实际 HTTPS server block 中保留 context：
```nginx
location /qatrack/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 5s;
    proxy_read_timeout 30s;
}
```
此处是单层 Nginx 边缘代理，覆盖客户端伪造的 forwarded headers；未来多层代理须重新设计可信链。proxy_pass 不带 URI，保留 /qatrack；ROOT 改用 location /。配置明确 server_name、证书和默认拒绝未知 Host 的 server；80重定向HTTPS。不要替客户端添加 X-QATrack-Request，也不要开启宽泛 CORS。Cookie/Set-Cookie 正常透传，不记录 Cookie、Authorization 或请求body。代理规则语义见 [Nginx 官方文档](https://nginx.org/en/docs/http/ngx_http_proxy_module.html)。

Tomcat Connector 绑定127.0.0.1:8080。建议在 Engine/Host 配置 RemoteIpValve，仅 internalProxies 匹配环回代理（本方案127.0.0.1），使用 remoteIpHeader=X-Forwarded-For、protocolHeader=X-Forwarded-Proto、protocolHeaderHttpsValue=https。不默认信任任意私网或客户端；代理头只有可信链路才能影响请求。配置依据 [Tomcat Remote IP Valve](https://tomcat.apache.org/tomcat-10.1-doc/config/valve.html)。

真实服务器尚需 nginx -t、Tomcat配置启动验证、scheme/isSecure与clientIP受控观测、外部伪造头测试。仓库无诊断HTTP端点，不新增公开 debug endpoint；用受限容器诊断/临时日志核对且不记录秘密。验证 Set-Cookie 有 HttpOnly/SameSite=Lax/Secure/正确Path，浏览器HTTPS登录后me成功。仅转发头或加Secure标记不足以替代整条代理信任验证。

## 8. Firewall / security group

阿里云安全组 + Ubuntu UFW + 实际监听同时检查：
- 公网仅22、80、443；22限管理来源/密钥登录。受控 smoke 的80/443同样限来源，或通过SSH隧道不开放HTTP公网。
- 不开放3306/8080；MySQL仅loopback，Tomcat仅127.0.0.1:8080。
- 检查IPv4/IPv6和已有Docker发布端口/转发规则，不能只凭UFW状态认定Docker端口未暴露。
- 调整防火墙前保留已验证的SSH管理入口，避免锁死；本轮没有执行调整。

## 9. Public-deployment blockers

分级说明：

- **A — 不影响受控 smoke deployment**：现有 WAR 的无前端、无分页与详情 N+1 不妨碍验证；公网加固未完成时，仅能在 SSH 隧道或严格来源限制下进行短时测试，不构成公网放行。
- **B — 阻止正式公网开放**：安全 ADMIN bootstrap、登录限流/抗爆破、HTTPS、Secure Cookie、可信反向代理验证、完整 CSRF token 或严格 Origin/Host 校验，均须完成并验证。ADMIN bootstrap 同时是登录 smoke 的前置条件，不能延期到公网阶段。
- **C — 一般后续增强**：分页、详情查询优化、自动化发布和监控仪表盘，可按后续范围安排；不把 B 类安全要求降为此类。基础日志检查和可用备份仍需在首次部署执行。

| 项目 | 受控 smoke | 正式公网开放 |
| --- | --- | --- |
| 安全 ADMIN bootstrap | 登录步骤前必须完成 | 必须完成 |
| 登录限流/抗爆破 | 仅限来源、测试账号、短时受控可暂缓 | **必须完成并验证** |
| HTTPS与Secure Cookie | 可通过SSH隧道测试纯HTTP，禁用公网明文登录 | **必须完成** |
| 可信代理 HTTPS/IP/Host验证 | 直连隧道可先验证应用；代理阶段必须核对 | **必须完成** |
| 完整 CSRF token 或严格 Origin/Host 策略 | 保留现有header+JSON+SameSite且限制访问 | **必须完成并验证** |

现有header策略不是完整CSRF交付；没有登录限流。上述公网事项不是普通TODO，不因本地262项通过而解除。安全bootstrap不能对“真实登录smoke”延期。受控测试不等于开放所有公网来源。

## 10. First cloud smoke checklist

先定context；以下以 /qatrack/api 为前缀。所有写请求带 X-QATrack-Request:1，JSON body用 application/json。客户端安全存储cookie jar，不在终端/log打印密码或Session ID。

首次服务器准备按以下顺序完成，再进入下方 HTTP 验证：

1. 初始化管理员确认 MySQL 版本/本机监听，创建 utf8mb4 / utf8mb4_0900_ai_ci 的独立空库。
2. 显式选择该库执行 schema.sql，确认 19 表；不执行 seed.sql。
3. 创建仅本机可连接的 qatrack_app，按第 4 节授予 DML 权限，用该账号核对连接、CURRENT_USER() 和隔离级别。
4. 准备受限外部配置、Tomcat unit/JVM 设置；确认运行用户能读取，HTTPS 模式显式 QATRACK_SESSION_SECURE=true。
5. 按第 5 节准备一次性 ACTIVE ADMIN，密码隐藏输入、hash 参数化写入；不启用公开默认凭据。
6. 备份既有 WAR/必要数据库数据及服务器配置，上传经校验的 WAR，按选定 context 命名；本轮未执行这些操作。

随后按顺序启动、验证与重启：

1. 核对上传WAR checksum、Java/Tomcat版本、config文件权限、DB版本/19表/隔离级别及运行账号授权。
2. 启动Tomcat，确认进程/loopback端口/context成功；排查Listener初始化错误。不要以系统进程存活代替应用成功。
3. 匿名GET /auth/me 返回401 JSON。它不能独立证明DB可用；实际登录与后续API才验证。
4. 验证Nginx→Tomcat相同响应，错误context不误当服务故障；正式HTTPS方案检查证书、forwarded信任和Cookie属性。
5. 完成ADMIN bootstrap。POST /auth/login 返回200及新Session；GET /auth/me 返回当前用户，GET /projects 返回200（空库可为空）。
6. POST /projects 创建明确标为smoke的测试项目；通过HTTP创建Requirement、TestCase/steps，将Case置READY；创建Plan并置READY。
7. POST /runs 从Plan创建；详情包含快照/NOT_RUN。追加FAIL并记录submissionKey；从FAIL创建Defect→start→resolve(note)。
8. 先尝试close应409；追加PASS后close成功。GET history仍含FAIL/PASS，证据仍指向FAIL。
9. 同key同payload重发得到原Attempt；不同payload409；完成Run后旧提交replay成功、新key失败。需要reopen时另建可执行Run产生新FAIL并单次reopen，核对BUG编号/旧证据/note。
10. 实测错误项目路径、无Session、缺header、stale version；不要在生产做高并发破坏性测试或执行Fixture清理。
11. 正常restart Tomcat，核对池/驱动清理及重新连接、DB数据保留；重新登录并重复GET。
12. Session只有actor ID、闲置30分钟、login轮换/logout失效。重启是否保留Session由Tomcat Manager持久化配置决定，仓库未强制“重启必失效”；先记录策略、实测旧cookie，安全轮换时明确清理旧会话。
13. 同一context redeploy，再查Listener/驱动/连接/登录与数据。不要留下旧展开目录或外部Context导致双部署；删除/移动前核对真实路径与备份。
14. 查启动/停止/重部署日志：SQLException、connection leak、JDBC driver leak、abandoned connection、Connector/J cleanup warning、Tomcat classloader warning、unexpected stacktrace、startup/shutdown failure；任何异常需定位后才能宣布smoke通过。
15. smoke数据通过现有业务归档或保留标识处理，无通用hard-delete API；不要为清理smoke数据直接删生产库。

## 11. Rollback / logs / troubleshooting

上线前保留上一WAR、checksum、配置与DB备份；初次部署也备份服务器既有配置/应用，避免覆盖已有ROOT。回滚先限制流量/停应用，恢复已确认的旧WAR与匹配外部配置，再验证。schema本轮未变，但不能假定未来跨版本数据库兼容；不要靠重复执行schema/seed回滚。首次空库初始化失败与有业务数据的回滚必须分别处理。

本次没有自动 migration，不引入 Flyway/Liquibase。新 WAR 启动失败时恢复上一份已知可用 WAR；真正首次部署若没有上一版，则停止新应用并恢复原服务器配置，保留数据库供诊断。DROP、清空数据库或删除业务数据都不是常规 rollback。

待确认日志位置：
- systemd：journalctl -u <实际unit>；不是必然有 catalina.out。
- Tomcat：CATALINA_BASE/logs，取决于实际logging.properties/服务包装器。
- Nginx：实际配置的access_log/error_log，Ubuntu常见 /var/log/nginx/。
- MySQL：@@log_error 或实际mysql unit journal；不猜测路径。

排障：502查Tomcat/监听；404查context；415查Content-Type；403查header/权限；401查账号/Session/Secure cookie；500查受限服务端日志，应用不会返回SQL细节。启动Database initialization failed检查配置选择、权限、密码、MySQL认证/TLS/连接数；勿把含秘密的配置、环境或日志全文贴进公开仓库。

## 12. Local handoff files / server facts still to confirm

本地准备：
- 已构建WAR + 本文checksum；Final Review后固定发布源码版本。
- database/schema.sql、本文及PHASE4-R3-EXECUTION-DEFECT-API.md。
- config/database.example.properties 仅作为无密码模板；现场制作生产文件，不上传本地开发/test credentials。
- 待现场确认后的Nginx/Tomcat/systemd配置、证书和一次性bootstrap操作材料；不是本轮新增或已经执行的脚本。
- 默认不带seed.sql；不上传target测试目录、Fixture、IDEA配置或整个开发工作区。

服务器仍需确认：Ubuntu版本/安装来源；Java路径；Tomcat unit、ExecStart、用户组、CATALINA_BASE、Context/Manager/Connector/Valve；MySQL精确版本/账号host/认证插件/TLS/空库/REPEATABLE-READ；域名DNS/证书/现有Nginx虚拟主机；防火墙/安全组/IPv6/Docker端口；资源余量；配置文件可读性；备份恢复位置与权限；bootstrap负责人及安全保管；公网blocker的实现与验收负责人。

## 13. Conclusion / 本轮验证边界

A. 当前WAR具备上传真实服务器做**受控smoke**的本地技术条件；仍等待Final Review与用户部署指令，未宣称云端已验证。
B. 准备WAR/checksum/schema/交接文档/无秘密模板，生产配置与bootstrap秘密现场安全生成。
C. 实际应用配置是六个数据库key、QATRACK_DB_CONFIG或qatrack.db.config、QATRACK_SESSION_SECURE；另需容器JVM/代理/TLS/防火墙设置。
D. 首次正式库不需要seed.sql。
E. 按现有PBKDF2格式一次性参数化插入ACTIVE ADMIN，无固定默认密码，无新增密码管理系统。
F. 限流、完整CSRF/Origin策略及公网TLS/代理加固可在访问严格受限的应用smoke阶段分步完成，但全部阻止正式公网开放；bootstrap仍是登录smoke前置条件。

本次补充仅修改文档，不重跑未变的Java/MySQL测试。沿用上一轮94项快速/262项全量和WAR双部署本地验证，不把它们写成云端验证结果。
