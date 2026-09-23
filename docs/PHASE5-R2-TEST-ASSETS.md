# Phase 5 Round 2 — Test Assets Frontend

Date: 2026-09-23. Scope: Requirements, Test Cases, current Test Steps, and Requirement ↔ Test Case traceability. The approved Phase 5 R1 session, API client, project picker, shell, and error mapping remain in use. No backend, schema, Maven dependency, or production database change was made.

## UI and files

- `src/main/webapp/index.html`: enabled Requirements and Test cases navigation; added list, detail, editor, step, and traceability regions inside the existing shell.
- `src/main/webapp/assets/js/app.js`: minimal hash view switching and project-context events. The shell still owns authentication and project selection.
- `src/main/webapp/assets/js/test-assets.js`: test-asset UI only. It calls the existing `VeriqraApi` client and has no independent session, fetch wrapper, or project selector.
- `src/main/webapp/assets/css/app.css`: asset list/detail/step/association layout, including narrow-screen wrapping.
- `src/test/java/io/github/lz007001cn/veriqra/integration/AuthHttpIntegrationTest.java`: verifies the new script and navigation in static resources served at `/` and `/veriqra`.
- `src/test/java/io/github/lz007001cn/veriqra/integration/TestAssetHttpIntegrationTest.java`: verifies that the approved requirement-side API can reconstruct a case's associations, preserving `CONFIRMED` and `REMOVED`.
- `src/test/java/io/github/lz007001cn/veriqra/web/WarDeploymentSmoke.java`: checks the new script's HTTP status and JavaScript MIME type in both packaged WAR contexts.

## Backend contract used

All paths below are relative to the existing context-aware `api/` client and begin with `projects/{projectId}`. The project ID comes from the R1 picker, and the server rechecks ownership/authorization.

| UI operation | Existing endpoint | Response / semantics |
| --- | --- | --- |
| Requirement list / create | `GET/POST requirements` | array / 201 RequirementResponse |
| Requirement detail / edit | `GET/PUT requirements/{id}` | RequirementResponse; PUT sends all editable fields and `expectedVersion` |
| Case list / create | `GET/POST test-cases` | array / 201 TestCaseResponse |
| Case detail / edit | `GET/PUT test-cases/{id}` | detail has `testCase` and `steps`; PUT replaces the complete current step aggregate and sends `expectedVersion` |
| Requirement links | `GET requirements/{id}/test-cases` | TraceabilityResponse array, including `REMOVED` records |
| Attach / reattach | `POST requirements/{id}/test-cases/{caseId}` with `{}` | 204; a removed record becomes `NEEDS_REVIEW` |
| Confirm / mark removed | `POST …/{caseId}/confirm` or `/remove` with `{}` | 204; remove is a state change, not deletion |

The API has no individual step write route, case-side traceability query, pagination, search, or manual “mark NEEDS_REVIEW” action. The UI does not invent them. It reads current requirements and their links to show traceability from a case. This is an N+1 read path; acceptable for the current course-size data, but a documented scalability limit. No step count is fetched for list rows.

The actual enum values used are requirement `DRAFT/ACTIVE/ARCHIVED`, case `DRAFT/READY/ARCHIVED`, traceability `NEEDS_REVIEW/CONFIRMED/REMOVED`, and priority `LOW/MEDIUM/HIGH`. Status badges always contain readable text. `REQ-` and `TC-` labels use the server's project-local `keyNo`; there are no fabricated records or IDs.

## Workflows and concurrency

Requirements and cases have list, detail, create, and complete-field edit forms. The case editor displays and replaces the full step set; add, edit, remove, move up, and move down renumber steps continuously from 1 before submission. Backend validation remains authoritative. Create/update success reloads detail from the server. Client-side required/title/step checks prevent obvious malformed submissions, and Save is disabled while a request is pending.

PUT sends the version read with the opened detail as `expectedVersion`. A 409 explicitly instructs the user to cancel and reload; the UI never retries with a newly read version or overwrites silently. Archive and link removal require a native confirmation naming the target. Traceability actions reload server state after 204. An existing `REMOVED` record is displayed and can be reattached; it is never presented as a hard delete.

On project selection, logout, pagehide, or session bootstrap, the asset module clears lists, detail, steps, and associations synchronously. It issues new reads only after R1 validates the selected project. A generation counter ignores responses from previous projects/views/details. Browser refresh and back/forward-cache restore rely on R1's session/project revalidation before business data becomes visible. All business routes are scoped to the selected project; role and ownership decisions remain in the Service layer.

API-controlled text is inserted with jQuery `.text()`, `.val()`, or `document.createTextNode()`. The only generated markup/class fragments are fixed local strings. There is no `innerHTML`, `.html()`, hardcoded `/api`, or hardcoded `/veriqra` in the new UI. Errors reuse R1's safe API mapping, including 401 login redirect and distinct 403/404/409/429/5xx messages.

## Responsive and accessibility

Lists and association rows wrap on narrow screens; detail fields become one column; step controls remain usable without horizontal page scrolling. Forms use explicit labels, required controls, visible focus, status text, `aria-live` feedback, and keyboard-reachable buttons. Native confirmation provides keyboard dismissal. This is a basic accessibility pass, not a full WCAG audit.

## Verification

Process-local Java 21 Windows socket workaround: `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=E:/Projects/QATrack-local-validation/tmp`. This did not change source or POM.

| Gate | Result |
| --- | --- |
| `mvn test` | 115 tests, 0 failures/errors/skips; BUILD SUCCESS |
| `mvn -Pmysql-tests test` | 288 tests, 0 failures/errors/skips; BUILD SUCCESS |
| `mvn -Pmysql-tests clean package` | 288 tests, 0 failures/errors/skips; WAR BUILD SUCCESS |
| Independent packaged WAR smoke | `ROOT.war` at `/` and `veriqra.war` at `/veriqra`; two deploy/stop cycles each; static/MIME/protected API PASS |
| Source ↔ WAR resource comparison | `index.html`, `app.css`, `app.js`, `test-assets.js` byte-for-byte match |
| Test DB | isolated `veriqra_test_r1` on localhost:3306, not development `veriqra`; final 0 tables |
| Temporary MySQL | no 13307 listener or 13307 `mysqld` process; none was started this round |
| `git diff --check` | PASS |

WAR: `target/veriqra-0.1.0-SNAPSHOT.war`
SHA-256: `305338A90146FED748937063D9385E56B19DF73516D0CC95B27CFB423A87D410`

## Manual browser acceptance

On 2026-09-23 the user reported passing the local IDEA Tomcat browser checks for login/project selection; Requirement list, create, detail, edit and refresh; Test Case list, create, edit and step add/edit/remove/reorder; traceability in both directions including status changes and removal; project switching; narrow viewport; logout and browser Back. This is **user-reported manual verification**, not an automated browser assertion. The separate packaged-WAR HTTP checks establish static/resource behavior at `/` and `/veriqra`; those two contexts were not claimed as manually browser-tested.

The user chose not to perform the two-tab stale-version scenario. Its **backend** behavior is covered by `TestAssetHttpIntegrationTest.requirementMaterialChangeInvalidatesAndStaleUpdateDoesNotOverwrite` and `caseStepReplacementDraftInvalidationAndValidationAreAtomic`: stale PUT returns 409 and saved data is preserved. The frontend's 409 message and no-auto-retry behavior were code-reviewed but were not exercised in the browser. Do not count the skipped manual scenario as a pass.

The disposable `veriqra_test_r1` schema was checked to contain only the two R2 demo users/projects and their manual data, then its 19 tables were dropped in reverse FK order with FK checks enabled; final table count is 0. The temporary standalone login-check Tomcat exited. The IDEA-managed Tomcat was not stopped by this cleanup; the final check found no port 8080 listener. The cleared test database no longer supports the demo logins.

## Open limitations and next review

- CRITICAL: none found.
- HIGH: none found.
- INFORMATIONAL: the user skipped browser execution of the two-tab 409 scenario. Backend conflict/no-overwrite behavior has real HTTP integration evidence; frontend conflict UX has code-review evidence only. Final Review assessed this as an explicit evidence limit rather than a product defect.
- LOW: case-side traceability uses one request per requirement, and asset lists are unpaged because the approved API has no reverse lookup/pagination. Data-heavy projects may need a later API enhancement based on measured need.
- INFORMATIONAL: Service status rules, project authorization, material-change review invalidation, and optimistic locking are unchanged. No Plan/Run/Execution/Defect/Automation UI was added.

The implementation is ready for the focused Phase 5 R2 Final Review. This document does not itself approve browser UX or authorize commit/push.
