# Veriqra Phase 4 Final Review

Date: 2026-09-23

This is the final review of the Web/API phase. It reviews R1 Authentication
and Servlet foundation, R2 Test Asset API, R3 Execution and Defect API, public
deployment security hardening, and R4 Automation and JUnit Import API. No
schema, persistence, Service, transaction, or production data changes were
made during this review.

## Final verdict

**Phase 4: APPROVED.** The production code uses the current Veriqra names only:
`X-Veriqra-Request`, `VERIQRA_*`, and `veriqra.db.config`. No `X-QATrack`,
`QATRACK_*`, or `qatrack.db.config` occurrence exists under `src/main`.
Occurrences in historical review/deployment documents are intentional factual
evidence and are not current compatibility behavior.

There are no code blockers before commit. Hong Kong ECS deployment remains a
separate deployment-side acceptance activity requiring the final domain,
TLS, proxy trust, firewall, and external HTTPS checks.

## Architecture

The HTTP path remains `Handler / Servlet -> Service -> DAO`. Handlers parse
HTTP input, enforce boundary shape, obtain the authenticated actor, map DTOs,
and map responses. They do not import or call DAO implementations and contain
no SQL. Authorization, project ownership, state transitions, optimistic
locking, idempotency, snapshot creation, and transaction boundaries remain in
the Service layer.

`ApplicationListener` creates one pool, DAO graph, Service graph, and WebServices
composition object, explicitly loads Connector/J, and closes owned resources on
startup failure and context destruction.

## Authentication and session

`POST /api/auth/login`, `POST /api/auth/logout`, and `GET /api/auth/me` use the
existing AuthService contract. The session stores only the actor user ID;
account status and authorization are re-read from the database. Login rotates
the session, logout invalidates it, idle timeout is 30 minutes, and responses
do not expose passwords, password hashes, or session IDs. Cookies are
HttpOnly, SameSite=Lax, and use the context path; Secure is controlled by
`VERIQRA_SESSION_SECURE`.

## Security gate

`AuthenticationFilter` applies the strict Origin/Host policy to every request
under `/api/*`. Every state-changing method (POST, PUT, PATCH, DELETE) requires
an authenticated session where applicable, one exact `X-Veriqra-Request: 1`
header, and Service authorization. Login is exempt only from an existing
session, not from the Origin or request-marker checks. No broad CORS is emitted.

The current public deployment model requires one exact HTTPS
`VERIQRA_PUBLIC_ORIGIN`, `VERIQRA_SESSION_SECURE=true`, a loopback-only Tomcat
upstream, and a correctly configured trusted proxy. Those external deployment
conditions are not claimed to be proven by local tests.

## Asset, execution, and defect APIs

R2 preserves project ownership, expected-version checks, contiguous TestCase
steps, traceability status transitions, and READY constraints through the
Service layer. R3 preserves READY plan scope, plan and ad-hoc Run creation,
immutable Run snapshots, terminal Run behavior, append-only Attempts, safe
attempt sequence allocation, submission-key replay/conflict semantics, and
Defect lifecycle/optimistic locking. PASS/FAIL evidence, close/reopen rules,
and role restrictions remain Service invariants.

## Automation and import API

R4 adapts the existing AutomationService and TestImportService:

| Method | Path | Access and semantics |
| --- | --- | --- |
| GET | `/api/projects/{projectId}/automation/identities` | Authenticated project reader; list identities |
| POST | `/api/projects/{projectId}/automation/identities` | Authorized project writer; register identity |
| GET | `/api/projects/{projectId}/automation/mappings` | Authenticated project reader; list mappings |
| GET | `/api/automation/identities/{identityId}` | Authenticated owner-project reader |
| GET | `/api/automation/identities/{identityId}/mapping` | Read active mapping |
| PUT | `/api/automation/identities/{identityId}/mapping` | Confirm or rebind a human mapping |
| POST | `/api/automation/identities/{identityId}/mapping/deactivate` | Deactivate mapping |
| POST | `/api/projects/{projectId}/imports/preview` | Authorized reader; parse/resolve only |
| POST | `/api/projects/{projectId}/imports` | Authorized writer; atomic formal import |

Automation identities are exact `(source, namespace, externalKey)` values and
remain project-owned. Unknown identities are returned by preview and never
implicitly create TestCases. Mapping changes are explicit human actions and
same-project constraints are enforced by Service code.

Preview maps PASS, FAIL, ERROR, and SKIPPED without creating identities,
TestCases, Runs, Attempts, or import records. Formal import creates the
TestImport, Run, immutable RunCase/RunCaseStep snapshots, and Attempts in the
existing outer Service transaction. Request-key replay returns the existing
result; different payload or metadata with the same key returns conflict.

## XML and JSON boundaries

Import routes require `application/xml`, accept parameters, and read at most
5 MiB. Empty, malformed, wrong-type, duplicate-parameter, and oversized input
are rejected with bounded HTTP errors. The parser disables DTDs, external
general/parameter entities, external DTD/schema access, and external resource
loading; parser failures become safe validation errors. JSON retains UTF-8,
64 KiB actual body bounds, unknown-field rejection, duplicate-field rejection,
wrong-type rejection, and trailing-token rejection.

Expected failures map to 400/401/403/404/409/413/415/429 as appropriate.
Internal failures return a generic 500 body without stack traces or JDBC/JSON
details. Rate-limit responses use 429 and `Retry-After` without disclosing
account, IP, or bucket state.

## Context portability

Routing uses the Servlet context path and does not hard-code `/qatrack` or
`/veriqra`. The verified deployment smoke covers both `ROOT.war -> /` and
`veriqra.war -> /veriqra`; Location headers, resource paths, and session cookie
paths are context-aware.

## Test evidence

The final Surefire reports contain 54 test-suite reports and 286 tests, with
zero failures, errors, or skips. `MysqlFixture` is the only `@Tag("mysql")`
fixture and the Maven profile changes `excludedGroups` from `mysql` to `none`.
Therefore plain `mvn test` and `mvn -Pmysql-tests test` currently execute the
same 286 tests: the default run is safe because the fixture is excluded, while
the profile explicitly opts into the isolated MySQL test environment. Neither
command targets `localhost:3306/veriqra`.

The report set includes HTTP authentication, asset, execution, and
Automation/Import integration tests; Service and DAO tests for requirements,
TestCases, Plans, Runs, Attempts, Defects, identities, mappings, and imports;
Origin, cookie, rate-limiter, XML-security, transaction rollback, and
concurrency tests. The Round 4 HTTP suite adds 3 passing tests. The isolated
test schema ends at zero tables and port 13307 has no listener.

## Build and WAR

The verified artifact is:

`target/veriqra-0.1.0-SNAPSHOT.war`

SHA-256:

`C1C17032B1C4EF5525963FC54B59918E74C7D28BE691D783D6DC43DB416EFC25`

The WAR contains production classes, Jackson, Connector/J, and `web.xml`; it
contains no test classes, local properties, credentials, Servlet API duplicate,
or embedded Tomcat runtime. `mvn -Pmysql-tests clean package` completed with
286 passing tests and `BUILD SUCCESS`. `git diff --check` passes.

## Findings

### CRITICAL

None.

### HIGH

None in application code. Public ECS deployment remains blocked until the
deployment checklist is independently verified on the target host.

### MEDIUM

None.

### LOW

Absolute session lifetime/global revocation, distributed rate limiting, and
edge DDoS controls remain future hardening items. They are outside the current
single-instance course scope.

### INFORMATIONAL

Historical documents retain QATrack names, old WAR/context paths, old
environment variables, and old checksums where those identifiers describe the
system at the time of the historical evidence. This is deliberate and is not
legacy compatibility in the current production code.

## Final answers

- **A. Phase 4 APPROVED?** Yes.
- **B. Commit blocker?** No application-code blocker. Review the complete diff before committing.
- **C. Round 4 APPROVED?** Yes.
- **D. Complete backend API baseline?** Yes for the implemented Phase 4 scope: Auth, assets, traceability, plans, runs, attempts, defects, automation, and JUnit import.
- **E. Security hardening complete?** The reviewed application hardening is intact; public deployment checks remain deployment-side.
- **F. MySQL/transaction/concurrency intact?** Yes; no schema, DAO, transaction manager, or persistence changes were made in this review.
- **G. ROOT and `/veriqra` supported?** Yes, both deployment modes were verified.
- **H. Commit/push Phase 4?** Yes after human review of this working-tree diff; this review did not commit or push.
- **I. Enter Phase 5 after commit?** Yes, after the Final Review is accepted.
- **J. Hong Kong public deployment independent?** Yes. ECS, DNS, TLS, proxy trust, firewall, external HTTPS, and production smoke remain separate deployment work.

## Working tree

No commit or push was performed. The working tree contains the Round 4
implementation, tests, and documentation changes described above.
