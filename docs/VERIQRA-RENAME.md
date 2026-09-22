# Veriqra rename and release verification

Product: **Veriqra — Software Test & Quality Management Platform**. Target public origin: `https://veriqra.xyz`.
This is a new release candidate, not the previous QATrack WAR. No server deployment, repository rename, commit or push is performed by this task.

## Identity and source changes

- Maven coordinates: `io.github.lz007001cn:veriqra:0.1.0-SNAPSHOT`, packaging WAR. Dependencies are unchanged.
- Artifact: `target/veriqra-0.1.0-SNAPSHOT.war`.
- Application and test packages/directories move from `io.github.lz007001cn.qatrack` to `io.github.lz007001cn.veriqra`; web.xml listener/filter/Servlet declarations move with them.
- README, application display name, build identity and JDBC handle display text use Veriqra. There are no implemented frontend pages, logos or screenshots to rebrand.
- ConnectionPool logic, transaction semantics, DAO SQL, schema structure and seed data are unchanged. SQL changes are branding comments plus the constraint verifier failure-message brand; no constraint or statement behavior changes.

## Configuration

Current configuration uses only `VERIQRA_*`. The temporary `QATRACK_*`, `X-QATrack-Request`, and `qatrack.db.config` compatibility aliases were removed before deployment because no deployed client or server configuration depended on them.

Database file selection: `VERIQRA_DB_CONFIG` > Java property `veriqra.db.config` > optional `config/database.local.properties`. Property values load in this order: classpath defaults < external file < `VERIQRA_DB_*` field overrides. Credentials remain external and excluded from Git/WAR.

Production configuration example (no credentials):

```ini
VERIQRA_DB_CONFIG=/opt/veriqra/config/database.local.properties
VERIQRA_PUBLIC_ORIGIN=https://veriqra.xyz
VERIQRA_SESSION_SECURE=true
```

The current physical database is `veriqra`; the DML-only runtime account is `veriqra_app`. Integration tests require an empty `veriqra_test_*` schema and a separately scoped test account. Do not rerun schema.sql or seed.sql against an existing deployment.

The write-request marker is exactly one `X-Veriqra-Request: 1`. It does not bypass mandatory Origin/Host checks, authentication or Service authorization. No CORS exception was added.

## Deployment and sessions

Copy the same release artifact as `ROOT.war` for `/`, or `veriqra.war` for `/veriqra`. API paths are relative to the selected context; session cookie Path is `/` or `/veriqra` respectively. HttpOnly, SameSite=Lax and explicit Secure configuration are preserved.

Future release storage: `/opt/veriqra/releases/veriqra-0.1.0-SNAPSHOT.war`; external configuration: `/opt/veriqra/config/`. These are target paths only, not evidence of changes on the Hong Kong server. The GitHub repository and local checkout directory have not been renamed by this task.

Plan a controlled restart and fresh login: old serialized sessions/package names and old context cookies are not promised compatible. Stop the old deployment and handle persisted sessions through the container deployment procedure. Do not serve old and new deployments unintentionally in parallel.

Nginx must preserve the chosen context, use `server_name veriqra.xyz`, valid TLS, HTTP-to-HTTPS redirect, overwrite forwarded headers, and proxy only to loopback Tomcat. Configure the narrowly trusted RemoteIpValve and verify external spoofed headers before removing access restrictions. See [current security policy](PUBLIC-DEPLOYMENT-SECURITY.md). This task did not exercise the real DNS/TLS/proxy/server chain.

Login limiter behavior is unchanged: in-memory per-instance fixed window, pair/IP budgets, concurrency reservation and 429/Retry-After. Redeployment resets it. Absolute session lifetime, global revocation, distributed limiter and frontend CSP tightening remain future work.

## Historical evidence

Historical review/acceptance reports retain their original text, including product/package names, old physical database identifiers, context paths, environment variables, deployment paths, test counts and SHA-256 values. A separate banner links here. Frozen-model names remain the historical **QATrack V1 Domain Model Freeze v1.0**; the model itself is unchanged.

[Occurrence audit](VERIQRA-LEGACY-NAME-AUDIT.tsv) classifies the remaining legacy-name matches by file and line. Ignored local credentials/build outputs and ignored submission/verification directories are excluded from the public-source audit.

## Verification

Initial source-rename validation completed locally on 2026-09-22 using JDK 21, MySQL 8.0.46 and Tomcat 10.1.60. The values in this first table describe the release candidate before the later local database-name migration.

| Check | Result |
| --- | --- |
| `mvn test` (final rerun) | 116 passed; 0 failures/errors/skips |
| `mvn -Pmysql-tests test` (completed interrupted run) | 284 passed; 0 failures/errors/skips |
| `mvn -Pmysql-tests clean package` | 284 passed; BUILD SUCCESS |
| Standalone packaged WAR | ROOT.war and veriqra.war; two independent start/stop cycles each; 401 JSON from protected API |
| Resource routing | Both WAR contexts: WEB-INF/web.xml and missing CSS return 404. No frontend/static assets exist yet; this does not claim positive frontend rendering coverage. |
| WAR inspection | 263 entries; 5 runtime JARs (four Jackson modules and Connector/J); new package/classes/web.xml; no old package classes, test dependencies, embedded Tomcat, Servlet API or local properties |
| Historical isolated database cleanup | MySQL reported 8.0.46/13307; the former test schema contained 0 tables before shutdown |
| Temporary server cleanup | mysqladmin shutdown succeeded; server log records normal shutdown; 13307 has no listener |
| `git diff --check` | PASS |

Coverage is deliberately distinguished by layer: AuthHttpIntegrationTest (3), TestAssetHttpIntegrationTest (8), and ExecutionHttpIntegrationTest (8) exercise real HTTP/Service/MySQL under ROOT. Existing non-root component suites exercise project/run/defect routing and permissions with controlled service doubles. PublicSecurityWebTest (6) checks login, session cookies, Origin rejection and limiter behavior. SameOriginPolicyTest (5) covers the canonical marker and rejected values; EnvironmentVariablesTest (2) covers current environment lookup; LoginRateLimiterTest (6) retains limiter edge-case coverage. Standalone WAR smoke validates real packaged classloader startup and shutdown in both contexts, not a second complete business workflow suite.

New test methods relative to the prior baseline: 5 (111 to 116 fast; 279 to 284 full). Existing context/header assertions were updated. Total tests are not added across Maven invocations.

Artifact SHA-256:

```text
1B3D38ABA5102BD71C9E5D812E5DFE8894376820EA2E7276BAD65BB47AC5583E
```

Initial source-rename evidence remains outside the repository under `<VALIDATION_ROOT>`. It predates the separately authorized local development-database migration below.

## Local development database migration

On 2026-09-22 the undeployed local MySQL 8.0.46 instance at `localhost:3306` was migrated after explicit authorization:

- A repository-external, single-transaction dump was created before any new database object. The dump contains 19 `CREATE TABLE` statements, is 113,302 bytes, and has SHA-256 `205A7977B79B7F99A4A66551883F1F7AD71EAEC543243EC850110E7DAB78064D`.
- The `veriqra` database was created with `utf8mb4_0900_ai_ci` and populated from that dump. All 19 tables match the retained source database row-for-row by `COUNT(*)`, totaling 680 rows.
- `veriqra_app` was created for local host connections with only SELECT/INSERT/UPDATE/DELETE on `veriqra.*`.
- Empty `veriqra_test_r1` and its separately scoped `veriqra_test` account were created for integration tests. The fixture uses only the `veriqra_test_*` prefix and left that schema at 0 tables.
- Random local passwords exist only in ignored `config/*.local.properties`; they are excluded from Git and WAR. The source database was retained as a rollback copy and was not deleted or modified.
- Post-migration results: `mvn test` 115 passed; `mvn -Pmysql-tests test` 283 passed; `mvn -Pmysql-tests clean package` 283 passed and BUILD SUCCESS; packaged ROOT and `/veriqra` smoke passed two deployment/stop cycles each; `git diff --check` passed.

The post-migration artifact is `target/veriqra-0.1.0-SNAPSHOT.war` (5,145,496 bytes), SHA-256:

```text
D13BDBF3DD82D86CC9F928CCB591AE0F21301E5F41BB9355820FAA9F6D9F6581
```

This was a local development migration only. No production server, cloud database, deployment, commit, push, or Phase 4 Round 4 work occurred.

## Findings and final decision

- CRITICAL: none found.
- HIGH: none found in the rename. Public deployment still requires the server-side TLS/proxy/security checks above.
- MEDIUM: none found.
- LOW: no frontend exists to positively verify static UI loading; existing optional Tomcat reflection/deprecated-API warnings remain.
- INFORMATIONAL: compatibility aliases were removed before deployment. Historical reports describe earlier products/artifacts and are not current deployment commands.

The source rename and local release verification are complete. This release candidate is ready for review and controlled deployment planning, not evidence that the public server has been upgraded or externally accepted. No Phase 4 Round 4 work, commit, push, production deployment or data migration occurred.


## Final source audit and working tree

The post-migration case-insensitive legacy-name search found 458 token occurrences in 35 files. Every occurrence is classified as intentional in the linked TSV: 385 preserved historical-report occurrences, 66 raw historical-verification occurrences, 6 migration-record occurrences, and 1 explicit historical validation identifier. No old product identifier remains in current application code, test code, examples, defaults, or active database tooling.

Twenty-seven historical report bodies were checked against HEAD and remain identical except for the separate migration banner. Current model documentation retains its original freeze identifier and date. The security policy explicitly separates current configuration from original validation evidence.

Working tree at completion (nothing staged): 42 modified tracked files, 266 deleted old-path files, and 270 untracked files. The deleted old source paths have corresponding new source paths; they represent the unstaged package move, not lost implementations. There are 200 main Java files and 62 test Java files in the new tree. Standard unstaged diff statistics omit new untracked paths: 308 tracked files changed, 119 insertions and 13,384 deletions. New-file whitespace was checked separately and passed. Local credentials and build outputs remain ignored and excluded from the WAR.
