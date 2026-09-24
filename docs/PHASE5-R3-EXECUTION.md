# Phase 5 Round 3 — Plans and Manual Execution

Date: 2026-09-23. Scope: Test Plans, plan membership, Test Runs, immutable Run Case snapshots, manual execution and append-only Attempt history. Phase 5 R1/R2 shell, session, project context and API client are reused. No schema, backend, Maven dependency or production database was changed. Defect and automation screens remain unavailable.

## Files changed

- `src/main/webapp/index.html`: enables Plans/Runs navigation and adds list, detail, editor, snapshot and Attempt regions.
- `src/main/webapp/assets/js/app.js`: adds the two views to the existing shell/project events.
- `src/main/webapp/assets/js/test-assets.js`: limits the R2 module to Requirement/Case views so it cannot read or render while R3 views are active.
- `src/main/webapp/assets/js/execution.js`: R3 presentation and existing API calls; no independent session or request client.
- `src/main/webapp/assets/css/app.css`: responsive selector, snapshot and history layout.
- `src/test/java/io/github/lz007001cn/veriqra/integration/AuthHttpIntegrationTest.java`: checks R3 navigation/script in both HTTP contexts and context-safe resource contract.
- `src/test/java/io/github/lz007001cn/veriqra/integration/ExecutionHttpIntegrationTest.java`: adds plan membership, snapshot and three-Attempt real MySQL regression.
- `src/test/java/io/github/lz007001cn/veriqra/web/WarDeploymentSmoke.java`: includes the R3 JavaScript resource and MIME in packaged WAR checks.

## Test Plan UI and membership

The UI uses existing `GET/POST /api/projects/{projectId}/test-plans`, `GET/PUT .../{planId}`, and the `POST .../test-cases/{caseId}` / `.../remove` / `.../archive` actions. Create sends `name`, optional `description`, and the selected current-project `testCaseIds`. Detail displays the returned `plan` and `testCaseIds`; one current-project Case list supplies labels and statuses. It shows actual membership count without one detail request per Case. Edit and every membership/archive action use the version read from detail. Every successful mutation rereads detail because membership changes can return the plan to DRAFT and increment its version. Stale version or invalid transition returns the server's 409; the UI does not retry with a newer version. READY selection remains subject to the Service's nonempty/all-READY checks. Archive requires a named confirmation.

The Case selector uses the existing unpaged Case list. It excludes archived Cases for plan creation/add. A READY Run source is selected from the existing unpaged Plan list. These are current project lists; the Service still enforces ownership and permissions.

## Test Run UI and creation

`GET/POST /api/projects/{projectId}/runs` supplies the list and creation. The form supports both actual backend origins: a READY `testPlanId`, or a nonempty set of READY ad-hoc `testCaseIds`. It sends exactly one origin with `name`, optional `environment`, and optional `buildVersion`. Run list shows server status, source type and creation time. It does not invent progress or a business Run number absent from `RunResponse`.

`GET .../runs/{runId}` supplies the entire `{run,cases}` projection. The Run detail renders returned snapshot cases and current outcome. Complete and cancel call the existing POST actions with `expectedVersion`, require a named confirmation, and reread the Run. The UI does not infer terminal-state rules; the Service requires every Run Case to have an Attempt before completion.

## Immutable snapshot

The Run Case screen uses only `RunDetailResponse.cases`: `snapshotTitle`, `snapshotDescription`, `snapshotPreconditions`, `snapshotPriority`, `capturedAt`, and `steps` with `stepOrder`, `action`, and `expectedResult`. It explicitly labels this content as the creation-time snapshot and never fetches a current TestCase detail to replace it. The API does not expose a frozen business Case key in this projection, so the UI identifies the source with its actual `testCaseId`. The new HTTP/MySQL regression changes the source Case after Run creation and asserts the full Run detail is unchanged.

## Manual execution, history and retest

For an IN_PROGRESS Run, the Case screen submits `POST .../runs/{runId}/cases/{runCaseId}/attempts` using the actual `PASS`, `FAIL`, `BLOCKED`, and `SKIPPED` enum. It sends optional nonnegative `durationMs`, `comment`, and a `failureMessage` only for FAIL. One disabled submit button prevents double clicks. Successful writes reread Run detail and the complete GET Attempt history; there is no optimistic status change.

Each execution is a new Attempt. History shows sequence number, text outcome, execution time, actor ID, duration, comment and failure message. The latest is marked, while every older result stays visible. Retest uses the same POST append route and a new `submissionKey`; no edit or delete route exists. Real HTTP/MySQL tests prove FAIL #1 → PASS #2 → FAIL #3 stays three ordered records, while SKIPPED is distinct. FAIL does not create a Defect in this round.

## Idempotency and errors

The browser generates one UUID `submissionKey` per execution intent. If the request fails and the user retries unchanged values in the same form, the same key/body is sent. A changed payload gets a new key; a successful record clears the pending intent. This follows the Service's same-key/same-payload replay rule. A page reload does not persist an uncertain pending key, so the user should inspect history before resubmitting after a lost response. The shared `VeriqraApi` still supplies context-aware URLs, `X-Veriqra-Request`, same-origin session cookies, 401 redirect and mapped 400/403/404/409/429/5xx errors. No R3 Ajax or authentication stack was added.

## Project, view and stale-state protection

The existing shell emits project/view events. Both asset modules clear their DOM and increment a generation on project switch, logout, pagehide, bootstrap and view change. R3 also increments the generation for list/detail/form/case transitions. Every asynchronous read or write completion checks its generation before rendering; an old Plan, Run, snapshot or history response cannot populate a newer project/view. On bfcache restore the R1 shell hides the page and revalidates session and project before either module loads business data. The shared project picker remains the sole owner of project context.

## DOM safety, responsive layout and accessibility

API-controlled names, snapshot text, steps, comments, actor IDs and errors enter the DOM via jQuery `.text()` / `.val()` or `document.createTextNode()`. Dynamic badge classes use a closed local map. R3 contains no `.html(serverText)`, `innerHTML`, hard-coded `/api`, or hard-coded `/veriqra`. No cookie, Origin, request-header, or server authorization code changed.

List and Run Case rows stack on narrow screens, snapshot/history fields wrap, and the selector scrolls internally. Controls have explicit labels or an accessible name, status has text as well as color, loading/errors use live regions, and buttons retain visible keyboard focus. Native confirmations are used for archive, membership removal and terminal Run actions. This is a focused course-level accessibility pass, not a full WCAG audit.

## Automated verification

| Gate | Result |
| --- | --- |
| `mvn test` | 115 passed; 0 failures/errors/skips |
| `mvn -Pmysql-tests test` | 289 passed; 0 failures/errors/skips |
| `mvn -Pmysql-tests clean package` | 289 passed; WAR BUILD SUCCESS |
| Packaged WAR smoke | `ROOT.war` at `/` and `veriqra.war` at `/veriqra`; two deployment/stop cycles each, protected API/static/MIME PASS |
| JavaScript syntax | `node --check` on app, assets and execution scripts PASS |
| `git diff --check` | PASS |
| Isolated MySQL | `veriqra_test_r1` final 0 tables; no development/rollback/cloud DB touched |
| Temporary processes | 13307 and 8080 no listener; no R3 validation process remains |

The tests use the isolated `config/database-test.local.properties`, guarded to `veriqra_test_*` and a non-root user. The process-only Java socket workaround was `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<LOCAL_VALIDATION_TMP>`; source and POM were unchanged. No temporary mysqld was started this round.

Artifact: `target/veriqra-0.1.0-SNAPSHOT.war`
SHA-256 after the 2026-09-24 Final Review rebuild: `9FA641F93699A549B49C1B42033EEF6686E414C0E0D93F632E07A2C02183885F`

## Manual browser smoke status

**User acceptance passed at `/veriqra_war` on 2026-09-24.** The user reported Plan create/edit/membership add/remove, Run create/detail, source Case edit with unchanged Run snapshot, FAIL → PASS → FAIL history, project switch/refresh/Back, narrow viewport and logout as passing. Before cleanup, the isolated test database independently showed the three ordered Attempt statuses and a snapshot title different from the current source Case title. The temporary acceptance tables were then removed; the test schema returned to 0 tables. The IDEA-managed Tomcat process was stopped by the user. Packaged `/` and `/veriqra` smoke was an independent HTTP/WAR check, not this interactive browser session.

BLOCKED/SKIPPED, double-submit, artificial late-response and malicious-content scenarios were not reported as manual browser tests. Relevant backend statuses and history behavior have automated coverage. See `docs/PHASE5-R3-FINAL-REVIEW.md` for the final evidence and decisions.

## Known limitations and R4 readiness

- LOW: Plan/Case selectors use unpaged lists; measure project size before adding a backend pagination/search contract.
- LOW: uncertain network submission keys live in the current form only. Inspect history after a reload before resubmitting.
- INFORMATIONAL: Run snapshots expose immutable content and source `testCaseId`, but no frozen business Case key; the UI does not fabricate one.
- INFORMATIONAL: interactive browser acceptance covered `/veriqra_war`; `/` and `/veriqra` were covered by packaged HTTP/WAR smoke.
- Existing R2 reverse traceability N+1 remains unchanged.

R3 implementation passed focused Final Review. Defect, evidence, automation and import UI belong to R4. No commit or push was performed in the review.
