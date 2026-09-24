# Phase 5 R5 — Administration Console

## Current-state audit

The Phase 4 baseline had session login/logout/current-user APIs, PBKDF2 verification, an Origin/Host/request-marker filter, role and status checks on each authenticated API call, and the 19-table frozen business model. It had no Admin route, user-list API, password creation helper, login/access/audit persistence, session registry, or Credit account. The existing `AuthService.current` loads the user on every protected request; an administrator disabling a signed-in user therefore blocks subsequent API calls without caching an obsolete role/status in the session.

R5 adds a separate Administration vertical slice. It does not change the 19 core business tables or their meanings. `Servlet/Handler → Service → DAO → JDBC` remains the path for business writes. `AdminServices` shares the existing pool and transaction manager and is installed by `ApplicationListener` without expanding the established `WebServices` constructor contract.

## Administration architecture and authorization

`/api/admin/*` has a single handler-level gate and a second service-level `AdminAccessPolicy` check. Missing session returns 401; an authenticated ordinary user returns 403; only an active system ADMIN may read or write. The sidebar entry appears only for ADMIN, while a direct non-admin HTML page visit shows a Forbidden state; the API remains the security boundary. Every write locks active ADMIN rows in ID order in the same transaction before changing roles/status. This serializes competing demotions/disables and protects the last active administrator invariant. The ordered lock adds contention to infrequent administrative writes but avoids an unprotected count/update race.

New user passwords and resets use the same PBKDF2WithHmacSHA256 format and 600,000 iterations as authentication, with a fresh random 16-byte salt. Admin responses use projections that never contain plaintext or hashes. Username is immutable; users are ACTIVE or DISABLED and never hard-deleted. Role/status/profile updates retain `lock_version` optimistic locking. Creating a user also creates its zero-balance Credit account and audit row in one transaction. Reset does not globally revoke already-issued sessions; existing sessions remain subject to live user role/status checks. Global revocation would require a session registry or credential epoch and is not claimed here.

## Additive database model

`database/schema.sql` is now the 24-table empty-install schema. The original 19-table DDL is preserved as `database/schema-v1-frozen.sql` for the historical 178-check V1 verification script. For an existing 19-table installation, `database/migrations/20260924-admin.sql` adds one users index, five tables, and zero-balance accounts for all existing users. It contains neither `USE` nor `IF NOT EXISTS`: select and back up the intended database, review, then execute once. R5 did not run it against development or production data.

On MySQL 8.0.46 the isolated migration produced 24 tables, 200 columns, 24 PK, 46 FK, 14 UNIQUE, 57 CHECK, and 79 total indexes, with the original 19-table semantics intact.

| Table | Responsibility and integrity | Query indexes |
| --- | --- | --- |
| `credit_accounts` | One row per user (PK/FK), nonnegative BIGINT balance and lock version | PK on user |
| `credit_transactions` | Append-only positive GRANT/RECLAIM ledger; FK to account and actor; unique `(batch_id,user_id)` | user/time, actor, type/time, batch unique |
| `login_events` | Best-effort SUCCESS/FAILURE/RATE_LIMITED attempts; unknown username may have null user FK | time, user/time, username/time, IP/time, result/time |
| `access_logs` | Best-effort request outcome; no body/query/cookie/header secrets | time, user/time, IP/time, status/time |
| `audit_logs` | Required, append-only administrative change record in the business transaction | time, actor/time, action/time, target |

The users `(system_role,status,id)` index supports ordered active-ADMIN locking. Credit checks enforce `balance >= 0` and `amount > 0`; type/result checks constrain their enums. FKs use RESTRICT, matching the existing historical-preservation policy. No log or ledger update/delete DAO API exists. Audit and Credit ledger rows are never edited to correct an error: make an opposite Credit transaction and keep both records.

Dashboard “today” counters use UTC, matching the JDBC session time zone.

These tables contain personal data (IP, User-Agent, activity). Proposed operational retention: access logs 90 days, login events 180 days, audit logs and Credit ledger long term according to institutional policy. R5 does **not** add a deletion job: retention requires a separately reviewed operation and should not silently remove evidence. Storage growth and privacy policy remain deployment decisions.

## API and UI

The HTML console lives under `/admin/`: Dashboard, Users, Experimental Credits, Login History, Access Logs, Audit Log, Sessions, Security, and System. It uses existing Bootstrap, jQuery, CSS tokens, table/card/modal patterns and the context-relative API client; both ROOT and `/veriqra` contexts are supported. Tables with unbounded growth use page/pageSize (maximum 100). Search inputs are bound parameters in DAO queries, never string-interpolated values. Data is rendered through text nodes rather than HTML insertion.

| API | Capability |
| --- | --- |
| `GET /api/admin/dashboard` | Users, auth, traffic, Credit metrics and recent events |
| `GET/POST /api/admin/users`, `GET/PATCH /api/admin/users/{id}` | List/create/view/update users |
| `POST /api/admin/users/{id}/reset-password` | PBKDF2 reset and required audit |
| `GET /api/admin/credits`, `/credits/summary`, `/credits/transactions` | Balances, totals and immutable ledger history |
| `GET /api/admin/credits/recipients` | Bounded active-user preview for a confirmed all-active batch |
| `GET /api/admin/users/{id}/credits` | Current account plus recent ten ledger entries |
| `POST /api/admin/users/{id}/credits/grant|reclaim` | Atomic single-user Credit changes |
| `POST /api/admin/credits/batch-grant` | Explicit selected-user or all-active scope, maximum 500 |
| `GET /api/admin/login-history`, `/access-logs`, `/audit-logs` | Filtered, newest-first, paged event data |
| `GET /api/admin/sessions` | Successful login activity and user-level last seen; no online-state claim |
| `GET /api/admin/security`, `/system` | Read-only security counts and safe system status |

Experimental Credits are internal integers with no cash value and no consumption, purchase, transfer, quota, or payment path. Grant/reclaim lock the account, validate bounds, update balance, append ledger, and append audit in **one** transaction. Batch issuance has one UUID shared by each ledger row, one audit row with bounded summary metadata, and a 500-recipient limit; any failure rolls back all recipients. The UI preserves selected recipients while paging, offers an explicit clear action, and shows recipient count, per-user amount and total before confirmation. An all-active batch sends its bounded preview ID list; the transaction recomputes the active list under the ADMIN write lock and rejects a changed list rather than issuing Credits to a different audience. The preview is compared as a set of IDs, so order alone does not cause a false conflict. Selected-user batches may include an explicitly selected disabled account; all-active batches exclude disabled accounts. Account amounts are BIGINT/Java long with checked arithmetic; aggregate SQL sums use `BigInteger`. Admin JSON represents Credit amounts as decimal strings so browser parsing does not lose precision. The UI accepts only safely representable positive whole-number inputs and formats returned values with `BigInt`; it never uses floating-point Credit arithmetic. The account and ledger tables keep separate page counters in the frontend.

The Sessions screen deliberately says `LOGIN_ACTIVITY_ONLY`. The existing Tomcat sessions have no reliable global registry, so it shows login time/user/IP/device and the user's latest observable request time. That last-seen value is user-level and may include another concurrent session; the screen does not assert online presence or offer force logout.

## Security and privacy review

New write routes inherit the existing strict Origin, Host, and `X-Veriqra-Request: 1` checks. They add no CORS policy. Both login and access telemetry use `request.getRemoteAddr()` after the trusted proxy boundary, never parse forwarded headers. The outer access filter generates a server-side request ID and captures the final status, including a container-generated 500 from an uncaught non-API failure. It stores the URI path without query string; telemetry also strips path parameters such as `;jsessionid` before persistence. No request body, password, hash, Cookie, Authorization, or session ID is persisted. User-Agent is truncated and parsed locally to coarse browser/OS/device classes; no fingerprinting service is used. Login attempts with unknown usernames have nullable `user_id` and a length-limited attempted name.

Telemetry is best-effort, so a logging outage does not change a login or API response. Admin audit is **not** best-effort: an audit write failure rolls back its user/Credit change. This difference is intentional. The System page exposes version, status, uptime, Java/Tomcat/MySQL versions, public origin and secure-cookie setting; it exposes no credentials, environment dump, connection strings or JVM arguments.

## Frontend final review

The existing Login, Projects, Requirements, Test Cases/Steps, Plans, Runs, Execution, Defects, Automation Mapping and JUnit Import remain on the common app shell; R5 adds only the ADMIN entry. Source and resource checks cover the Administration sidebar and all nine pages under both context paths. Tables have local horizontal overflow, long text wraps, mobile navigation retains the existing hamburger breakpoint, forms/modals have visible labels, loading/empty/error states, and the admin API client retains context-relative URLs. Key R5 interactions were also checked in a real browser on the isolated build; automated resource and HTTP tests did not replace that check.

| Frontend area | R5 review evidence |
| --- | --- |
| Login and project shell | Existing local browser acceptance, unchanged shared auth/API client; ADMIN link is hidden by default and revealed from live `/auth/me` role |
| Requirements, cases/steps, traceability, plans | Existing R2/R3 browser acceptance and unchanged modules; shared sidebar, error, empty and responsive CSS inspected |
| Runs, snapshots, attempts, defects | Existing R3/R4 browser acceptance; append-only history and snapshot UI unchanged; long-page sticky sidebar remains in common CSS |
| Automation identities/mappings and JUnit import | Existing R4 browser acceptance, including unknown/mapped previews, ERROR/PASS import outcomes and project isolation; module unchanged |
| Admin Dashboard, Users, Credits | HTTP/MySQL tests plus isolated browser verification of user state, form defaults, Credit changes, ledger and batch confirmation |
| Login History, Access Logs, Audit Log, Sessions, Security, System | HTTP assets/authorization plus isolated browser verification of page load, filters, paging, read-only status and activity-only session wording |

The prior R4 user browser acceptance does not certify new R5 interactions. R5 was therefore checked separately on an isolated installation, including modal Escape/close, narrow-screen navigation, batch confirmation, pagination and filter reset. Production deployment remains a separate gate.

### Isolated R5 browser acceptance completed

An isolated Tomcat deployment of the locally built WAR was exercised against the disposable `veriqra_test_r1` schema. ADMIN login, all nine Administration pages, user status disable/enable, Credit Grant/Reclaim, ledger and audit records, Login History and Access Logs filters, Access Logs pagination, Sessions wording, Security metrics, System status, browser back/refresh, and mobile hamburger navigation were verified in a real browser. The Create User modal initially exposed two UI defects: ordinary text lost its first `T`, and role/status had no selected value. Both were fixed locally: only ISO timestamp text is reformatted, and select fields initialize to their first defined option. A fast modal submission could also leave a successful modal open during its opening transition; the hide action now waits for `shown.bs.modal` when necessary. The rebuilt WAR was rechecked in the browser: `R5 Acceptance User`, `ACTIVE`, and the `USER`/`ACTIVE` modal defaults display correctly; Grant 10 Credits showed balance 10 and a ledger entry. Reclaim 3 was subsequently confirmed by a fresh page load: balance 7, matching ledger and audit rows. The first browser automation view had timed out during the native confirmation and was stale; the fresh database-backed view resolved that uncertainty.

The user separately confirmed that Reclaim and selected-user Batch Grant succeeded in Edge against this disposable instance. After those actions, the fresh Users page showed a balance of 5, consistent with the additional Reclaim 3 and Batch Grant 1; the ledger carried a batch ID and the audit page showed `CREDIT_BATCH_GRANTED`. The user then performed the credential handoff: resetting the test USER password, confirming the old password was rejected and the new password worked. As that ordinary USER, the Administration navigation was absent and a direct `/admin/index.html` visit was denied. The independent HTTP tests also verify `/api/admin/*` returns 403 for ordinary USER. No new password was disclosed to the assistant or stored in the repository. These results close the R5 browser acceptance checklist for this isolated build.

## Verification and handoff

- Isolated MySQL service tests cover user creation/duplicate/last-admin/optimistic lock/password reset, Credit atomicity/batch rollback/concurrent reclaim, required-audit failure rollback, and telemetry.
- Real HTTP tests cover 401/403/ADMIN, secret-free user DTOs, Credit and audit flows, Origin/marker denial, logging privacy, and static resources under ROOT and `/veriqra`.
- `database/verify-admin-migration.ps1` requires the empty `veriqra_test_r1` schema and scoped test account, applies historical V1 DDL plus migration, checks 24 tables and account backfill, then drops only the tables it created and verifies 0 tables.
- Isolated browser acceptance: ADMIN login, all nine pages, test USER creation and status changes, password reset with old/new-login checks, single and selected-user batch Credit operations, ledger/audit, log filtering/paging, narrow-screen navigation, refresh/back, logout, and direct Administration page denial for ordinary USER. Ordinary USER API denial is covered by the HTTP tests above. No production account or database was used.

Final local verification on 2026-09-24:

| Check | Result |
| --- | --- |
| `mvn test` | 115/115 pass |
| `mvn -Pmysql-tests test` | 308/308 pass |
| `mvn -Pmysql-tests clean package` | 308/308 pass, WAR build success |
| Isolated V1 → R5 migration | 19 → 24 tables; 200 columns, 24 PK, 46 FK, 14 UNIQUE, 57 CHECK, 79 indexes; old-user account backfill pass |
| Standalone WAR smoke | ROOT and `/veriqra`; two independent deployment/shutdown cycles each; resources and protected API pass |
| JS syntax, `git diff --check`, WAR contents | Pass; 12 `admin/` entries, no local credential config |
| Isolated Maven test schema and temporary MySQL | 0 tables after suites; no temporary mysqld or port 13307 listener |
| Isolated browser acceptance and cleanup | Real browser checks pass; Tomcat stopped; disposable test schema 0 tables; ports 9006 and 13307 have no listener |

The latest local WAR is `target/veriqra-0.1.0-SNAPSHOT.war`, SHA-256 `D1E7D3A2EAE99EA785C7FCD9AA507704845BA8A6471AE68B1B5BC2B8FC61D095`. Recalculate the digest after any rebuild; this is a local verification artifact, not a production deployment.

Final review found and fixed four narrow issues: the all-active batch preview comparison rejected a reordered but unchanged ID list; an access URI path parameter such as `;jsessionid` could enter the access log; an uncaught non-API failure could be logged as 200 even when Tomcat returned 500; and Credit account pagination shared its page number with the ledger table. Isolated service and HTTP regressions cover the first three, JavaScript syntax and packaged resource checks cover the fourth. A required-audit failure rollback test was also added. The existing browser acceptance predates these final fixes; no new production deployment or browser session was used for the review.

The separate browser-acceptance fixture used loopback `127.0.0.1:9006` and 24 disposable test tables. After human credential verification, its Tomcat process stopped and the owned tables were removed; the test schema was independently inspected at 0 tables. Neither the development nor production database was touched.

R5 creates local code and a migration artifact only. Production deployment, migration execution, retention job and global session revocation are separate reviewed steps.
