# 仓库与环境审计

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

检查日期：2026-09-06（Asia/Singapore）。本轮仅审计和修复最小骨架。
本文记录当日实测结果；后续环境或代码变化后需要重新验证。

## 修改前的仓库

- 根目录：`<PROJECT_ROOT>`，分支 `main` 跟踪 `origin/main`，工作区干净。
- 本地 HEAD 与 `git ls-remote origin refs/heads/main` 均为
  `9fd1cf43e808786c7bd8b86c7c7cf9fa81b20a3b`。
- 远程：`https://github.com/lz007001cn/QATrack.git`，fetch/push 地址一致。
- 历史：`9fd1cf4 test`、`3e7aa47 test`、`5203508 Merge pull request #1 from INYerty/main`、
  `e74a5fe 测试pr`、`bee8cdc Initial commit`。后续提交信息应描述实际改动。
- 跟踪文件仅 `.gitignore`、`README.md`、`pom.xml`。
- 本地 `.idea/` 未被跟踪，无须从 Git index 移除文件。
- 无源码、已有 Servlet 体系约束或适用的 `AGENTS.md`。

已执行 `git status`、`git log --oneline --decorate -n 10`、`git remote -v`、
`git ls-files`、工作区/暂存区 diff，以及目录和配置文件检查。

## 环境实测

| 项目 | 结果 |
| --- | --- |
| 默认终端 Java | Microsoft OpenJDK 11.0.16.1 |
| 默认终端 Maven | `mvn` 无法识别，未加入当前 PATH |
| 可用 JDK 21 | Microsoft OpenJDK 21.0.12.1，实际运行成功 |
| JDK 21 路径 | `<JDK_21_HOME>` |
| 可用 Maven | IDEA 自带 Apache Maven 3.9.16，实际运行成功 |
| Maven 路径 | `<MAVEN_HOME>` |
| IDEA 项目 | 已关联 `pom.xml`，SDK 为 `ms-21`，语言级别 JDK 21 |
| IDEA Maven importer / runner | 已检查项目文件中未发现显式 JDK 覆盖；未通过 IDEA UI 验证实际执行 |
| MySQL | `MySQL80` 服务运行中，对应服务二进制版本为 8.0.46 |
| MySQL 8.4 LTS | 未验证，不等同于现有 8.0.46；未连接数据库或修改服务 |
| Tomcat | 环境变量、项目配置和 Windows 服务检查中未发现；未全盘搜索或验证部署 |

项目代码和 WAR 在 E 盘；现有 JDK 与 Maven 用户缓存仍可能位于 C 盘。
本轮复用已有工具，没有迁移 JDK、修改全局环境变量或改动本地 IDEA 设置。

## 本机复现命令

以下设置只对当前 PowerShell 会话生效。IDEA 升级或 JDK 路径变化后需调整路径。
其他开发者应使用自己的 JDK 21 和 Maven 3.9.x。

```powershell
Set-Location -LiteralPath '<PROJECT_ROOT>'
$env:JAVA_HOME = '<JDK_21_HOME>'
$qatrackMavenBin = '<MAVEN_HOME>/bin'
$env:Path = "$env:JAVA_HOME/bin;$qatrackMavenBin;$env:Path"

java -version
mvn -version
mvn validate
mvn clean package
```

## 问题、修复与验证

1. 原 POM 坐标、WAR packaging、`release=21` 和 UTF-8 正确。
   使用上述 JDK/Maven 后，原始 `mvn validate` 成功。
2. 原始 `mvn clean package` 失败：
   `webxml attribute is required (or pre-existing WEB-INF/web.xml if executing in update mode)`。
   原项目无描述符，也无 Servlet API 依赖使插件推断无描述符模式。
3. 保留 Maven 坐标和 Java 目标，固定 Compiler Plugin 3.15.0、WAR Plugin 3.5.1，
   显式设置 `failOnMissingWebXml=false` 支持当前空骨架。
4. 创建指定目录，以 12 个 `.gitkeep` 保留空目录；WAR 排除 `**/.gitkeep`。
   没有占位 Java 类或应用依赖。
5. 修改后使用 JDK 21.0.12.1、Maven 3.9.16 重新执行 `mvn validate` 和
   `mvn clean package`，均退出 0，显示 `BUILD SUCCESS`。
6. 生成 `target/qatrack-0.1.0-SNAPSHOT.war`，本次 1,527 字节。
   ZIP 检查仅含 WAR 目录及 Maven/Manifest 元数据，没有 `.gitkeep`、`.idea`、类或依赖 JAR。
7. Maven 报告 `No tests to run`。当前没有源码和测试，不能据此声称业务测试通过、
   Java 源码完成编译验证或覆盖率达标。
8. 原 `.gitignore` 满足全部指定项，11 项路径探针通过，
   `git ls-files -- .idea target out` 无输出。`git diff --check` 通过。
   LF/CRLF 提示来自现有 Git 换行策略。

修改文件：

- `pom.xml`：固定两个插件，修复无描述符打包，排除占位文件。
- `README.md`：将初始化测试文字替换为真实项目说明、目录和构建方法。
- `docs/ENVIRONMENT-AUDIT.md`：本报告。
- `src/main/java/io/github/lz007001cn/qatrack/{model,dao,service,servlet,util,exception}/.gitkeep`。
- `src/main/resources/.gitkeep`、`src/main/webapp/WEB-INF/.gitkeep`。
- `src/main/webapp/static/{css,js,images}/.gitkeep`、`src/test/java/.gitkeep`。

未改 `.gitignore` 或 `.idea`，未暂存、commit、push、创建 tag/release。
最终工作区为 `README.md`、`pom.xml` 修改，以及 `docs/`、`src/` 新文件。
构建产物保持忽略。

## 技术边界与下一步

按用户默认要求，后续采用 JDK 21、Tomcat 10.1 和 Jakarta Servlet 6.0。
当前保持零应用依赖；首个 Servlet 实现时再加入 `jakarta.servlet-api:6.0.0`
及 `provided` scope，不混用 `javax.servlet.*`。
兼容性见 [Apache 版本说明](https://tomcat.apache.org/whichversion)，
打包参数见 [WAR Plugin 文档](https://maven.apache.org/plugins/maven-war-plugin/war-mojo.html)。

本轮无须额外确认架构决策。数据库开发前需确定 MySQL 8.4 的独立安装位置、端口及
凭据管理方式，避免影响现有 MySQL 8.0 服务；部署前需配置并实测 Tomcat 10.1。
默认终端仍需执行上述会话设置，或后续单独统一工具链配置。

下一阶段先做 V1 领域分析，明确需求与用例多对多、计划与执行的区别、结果与缺陷关联、
项目边界及事务，再给出可评审的关系模型、3NF 说明、约束和索引查询依据。
评审后按小阶段实现业务和相应测试，不一次性生成所有模块。

## 课程资料对照

来源：用户提供的 `课程要求.pptx`，已提取全部 12 页文本。
以下为后续验收约束，不表示本轮已实现：

- 第 4 页：2 人小组、分工表、教师数据库评审、至少 3NF、每表至少 10 条测试数据。
- 第 5–6 页：DAO 接口/实现分离、可参数化连接池、JavaDoc、Service 业务异常、
  Servlet HTTP 职责、Jackson 和统一 JSON 响应。
- 第 7–8 页：管理端与用户端、Bootstrap Table、ECharts、BEM、JSDoc、AJAX。
- 第 9 页：JUnit 5 + Mockito、核心业务覆盖率至少 60%、JMeter 100 并发、
  至少 3 项性能瓶颈修复及前后数据、WAR/Tomcat 部署；JaCoCo 是用户补充的统计工具。
- 第 10–12 页：Issue、交叉审查、提交历史、文档与答辩；
  数据库 30%、后端 40%、前端 20%、文档与答辩 10%。

PPT 第 2 页的层次排列不作为实际调用链。按用户明确要求，调用方向为
Servlet → Service → DAO → JDBC → MySQL，Model 承载数据。
课程中的功能示例不自动扩展本轮任务或 V1 范围。
