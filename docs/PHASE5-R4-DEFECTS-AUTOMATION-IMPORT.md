# Phase 5 Round 4 — Defects, Automation and JUnit Import

Date: 2026-09-24. This document records the R4 implementation baseline and subsequent browser acceptance; the focused Final Review is recorded separately.

## 1. Scope

R4 adds Defect list/create/detail, linked FAIL evidence and lifecycle actions, retest/closure visualization, automation identity registration/list/query, human-confirmed mapping/deactivation/rebind, JUnit XML preview and formal import. R1–R3 navigation, session, project context, TestCase editing and append-only manual execution remain in place. No schema, DAO, Service, Servlet, security filter, Maven dependency or ECS configuration changed.

## 2. Files changed

Production web files: `src/main/webapp/index.html`, `assets/css/app.css`, `assets/js/app.js`, `assets/js/api.js`, `assets/js/execution.js`, new `assets/js/defects.js` and `assets/js/automation-imports.js`. Tests: `AuthHttpIntegrationTest`, `AutomationImportHttpIntegrationTest`, `WarDeploymentSmoke`. This document is new.

## 3. Defect UI

The existing Shell now enables Defects. The list uses `GET /api/projects/{projectId}/defects` and displays only actual `DefectResponse` fields, including BUG key, title, status, severity, priority, version and timestamps. Create uses `POST .../defects` with a selected same-project FAIL Attempt, title, description, severity, priority and optional assignee ID. The evidence chooser drills from Run to immutable Run Case snapshot to its Attempt history, and only offers `FAIL`. A FAIL history row in R3 has a direct Create Defect entry that preselects that exact Attempt. The Service remains the final authority for project, role and evidence validity.

## 4. Evidence

Detail starts from `GET .../defects/{id}`. Its `evidence` projection provides Attempt/Run/Run Case IDs, Attempt sequence/outcome, failure message and link time. To explain retesting, the UI then reads the existing Run detail and Run Case Attempt history; it uses the frozen snapshot title and shows all subsequent results, execution time, actor and comments where available. Failure of an enrichment read leaves the linked evidence visible rather than substituting current TestCase content. `POST .../evidence` links another FAIL; the explicit correction action removes a link, not the historical Attempt.

## 5. Lifecycle

Actions follow the exposed operations: OPEN/REOPENED → `start`, IN_PROGRESS → `resolve` with note, RESOLVED → `close`, RESOLVED/CLOSED → `reopen` with a new FAIL and assignee. The UI sends the read `expectedVersion` on versioned actions. A 409 requests a review of the latest detail; it never silently retries with a newer version. Status visibility improves usability, while Service permission and transition checks remain authoritative.

## 6. Closure and retest

The UI does not compute a closure decision. The Service checks that every linked FAIL has a later *current* PASS on its Run Case and that the Defect is RESOLVED with a resolution note. The detail shows linked failures and later retests so a rejection is explainable. Retest itself remains R3's append-only Attempt POST. A subsequent FAIL blocks close even if an earlier PASS exists. Reopen requires a new unlinked FAIL; the selector excludes currently linked evidence, and the Service validates it again.

## 7. Automation Identity

The Automation & Imports workspace uses the existing project-scoped identity list/register routes and identity query route. Registration sends only `source: JUNIT`, `namespace` and `externalKey`. An identity is an external test identity, not a TestCase; registration alone never maps one.

## 8. Mapping

The workspace reads project identities, mappings and current-project TestCases. It shows identity, mapping status/version and mapped TestCase, with Case key/title/status in the human selector. An unmapped identity can be explicitly confirmed via `PUT /api/automation/identities/{id}/mapping` with `expectedVersion: null`; an inactive mapping can be reactivated/rebound with its returned version. Active mappings must be deactivated through the versioned action before remapping. A mapping referenced by execution history may be blocked from rebind by Service. No name matching or automatic mapping occurs.

## 9. JUnit preview

The shared API client gained one raw XML transport helper: context-relative URL, `POST`, `Content-Type: application/xml`, `processData: false`, existing request header/session/error handling. It sends the selected `File` bytes to `POST /api/projects/{projectId}/imports/preview?sourceNamespace=...`. The browser does not parse XML. Preview renders mapped results from the actual DTO, including outcome/duration/messages/TestCase, unknown identities separately, and invalid-entry issues. Unknown identities do not carry per-result status in the current preview DTO; the UI does not invent it.

## 10. Preview side effects

Preview invokes only the read-only analyze endpoint. It never calls identity registration, mapping, Run, Attempt or formal import. Unknown identities can prefill an explicit registration form, but the user must submit registration and confirm mapping, then preview again. The HTTP/MySQL regression checks that an unknown Preview creates no TestCase, identity, mapping, Run, Run Case, Attempt or Import row.

## 11. Formal import

The UI sends the same raw XML file to the single formal `POST /api/projects/{projectId}/imports` operation with URL parameters `requestKey`, `sourceNamespace`, `originalFilename`, `runName` and optional environment/build. It requires a ready Preview for the same File and namespace. The backend transaction creates the Import, completed Run, frozen Run Cases/steps and Attempts together; the frontend never splits this into multiple writes. Success displays returned Import ID, Run, status, Run Case count, Attempt count and replay status.

## 12. `requestKey` and idempotency

One UUID belongs to a selected file plus its unchanged import fields. A failed formal request retains it, including when the same file is previewed again. New file/namespace/import fields create a new intent. The submit button and fields are disabled during the in-flight request. The backend returns a replay for the same key/payload and 409 for conflicting payload. A page refresh or project/view switch does not retain an uncertain key; after an uncertain response the user should check Runs before starting a new import.

## 13. R3 Runs integration

The import result's Open Run action navigates into the existing R3 Runs detail. Defect evidence also offers Open source Run. Neither path builds an alternative Run/snapshot/Attempt viewer or edits an old Attempt.

## 14. Project and view isolation

Both new modules consume R1 project/view events. Switching project or view clears Defect detail, evidence, mapping controls, selected XML, Preview, pending Import result and errors. Generation checks discard late reads/writes from an obsolete project/view. The R1 pagehide/pageshow session revalidation still protects bfcache; sensitive XML preview state is cleared by the project-null event.

## 15. Security and XML boundary

All server/user text enters through `.text()`, `.val()` or safe element creation; no XML is inserted as HTML, logged or stored in localStorage. The browser's 5 MiB check gives early feedback; the backend independently enforces 5 MiB, `application/xml`, DTD/XXE/external-resource rejection, Origin, request marker, session and permissions. The client maps 400/401/403/404/409/413/415/429/5xx to safe messages. The backend masks detailed Service conflict reasons, so the UI describes likely closure requirements without claiming to know the exact cause of a 409.

## 16. Responsive and accessibility

Lists and evidence cards wrap on narrow screens; mapping selectors constrain width and buttons wrap. Forms have labels, status is written as text plus color, and errors/loading use live regions. All actions are native buttons/selects; focus visibility remains provided by R1 CSS. This is a basic usability pass, not a full accessibility audit.

Browser acceptance exposed a LOW desktop layout issue: `.app-body` grows with long main content, and its flex child `.sidebar` stretched to that full height, so document scrolling carried the navigation away. The desktop-only CSS rule now gives Sidebar `position: sticky`, `top: 68px`, `align-self: flex-start` and `height: calc(100vh - 68px)`; shared `overflow-y: auto` lets an overlong Sidebar scroll independently. Topbar and main remain in their original flow. At the existing `max-width: 991px` breakpoint, the hamburger and fixed Sidebar rules still apply; no JavaScript changed for this fix. A temporary browser-driven layout fixture using the real Shell markup and CSS checked long Dashboard, Run detail, Snapshot/Attempt, Automation and Defect pages at 1440×800 and 390×800, plus the 992/991px breakpoint boundary. All checks kept the topbar at 0, desktop Sidebar at 68px with independent scrolling, mobile Sidebar fixed below the topbar with the hamburger visible, main scroll independent and no document horizontal overflow. This is a layout regression check, not a claim of a second authenticated browser session after the CSS change.

## 17. Automated tests

Existing real HTTP/MySQL regressions cover Defect create/list/detail/version/lifecycle, unresolved FAIL close rejection, later PASS closure, later FAIL rejection, reopen with new FAIL, project authorization, identity/mapping operations, unknown Preview, all JUnit outcomes, replay/conflicting payload, malformed/DTD/oversized XML and atomic rollback. R4 extends the Preview no-side-effect assertion to all relevant tables. Resource tests include both new scripts, script MIME, navigation and context-relative raw XML contract. Final command counts and WAR evidence follow below.

## 18. Browser smoke status

The user reports the R4 business browser acceptance complete: Defect lifecycle; FAIL → PASS → Close; later FAIL → Reopen; linked evidence history; unknown JUnit Preview; explicit Identity registration; human-confirmed Mapping; mapped Preview; formal Import; imported Run, immutable Run Case snapshot and Attempt history; project isolation; refresh/browser history; basic responsive behavior; and logout protection all passed. The imported ERROR Case showed Attempt #1 FAIL with failure text `[JUnit error] setup error` and `Fixture setup failed`; the imported PASS Case showed Attempt #1 PASS. The browser run used an external temporary five-case JUnit XML sample (`pass`, `failure`, `error`, `skipped`, `unknown`). Preview no-side-effect behavior is additionally covered by the HTTP/MySQL regression.

During the lifecycle check, the user did not see the premature-Close rejection or later Close success message. A read-only isolated-database check confirmed the Defect was CLOSED with FAIL #1 and PASS #2 preserved. The old rejection notice was at the top of the workspace, outside the scrolled action area, and successful detail reload cleared feedback. A localized action-adjacent live region now reports both outcomes; a separate authenticated-browser recheck of that feedback change has not been claimed. The later Sidebar scroll coupling issue was found during this browser acceptance and fixed as recorded in section 16; the ten browser-driven layout checks passed after the CSS fix.

## 19. ROOT and `/veriqra`

The new script resources are included in the existing independent packaged-WAR smoke for both ROOT `/` and named `/veriqra` contexts. This HTTP/resource smoke is distinct from interactive browser acceptance. The final results are recorded below.

## 20. Known limitations

The API has no independent import-history list, user/member selector for assignee names, or frozen business Case key on Run Case projection. The Defect UI accepts an assignee user ID and the backend validates the active Developer/Admin requirement. Lists remain unpaged. A full-page refresh does not preserve an uncertain Import request key, as with R3's Attempt key. Evidence enrichment makes additional reads only for a selected Defect. Backend 409 responses deliberately hide detailed business reasons; the UI cannot distinguish stale version from failed close evidence solely from the response.

## 21. Final-review handoff

R4 business browser acceptance and the Sidebar layout regression checks are recorded. The focused review, fresh full verification, isolated fixture cleanup and final artifact hash are in `PHASE5-R4-FINAL-REVIEW.md`. No R5 work, public deployment, commit or push was performed during this review.

## Final verification

| Gate | Result |
| --- | --- |
| `mvn test` | Final Review rerun: 115 passed; 0 failures, 0 errors, 0 skipped |
| `mvn -Pmysql-tests test` | Final Review rerun: 289 passed; 0 failures, 0 errors, 0 skipped |
| `mvn -Pmysql-tests clean package` | Final Review rerun: 289 passed; WAR `BUILD SUCCESS` |
| Packaged WAR | `ROOT.war` at `/` and `veriqra.war` at `/veriqra`, two independent deployment/stop cycles each; resources, MIME and protected API passed |
| JavaScript | `node --check` for the modified/new application scripts passed |
| `git diff --check` | Passed (Git printed only line-ending conversion notices) |
| Isolated MySQL | The recorded 19-table manual-acceptance fixture was retired before the Final Review MySQL suites; `veriqra_test_r1` ended with 0 tables. Development, rollback and cloud databases were untouched |
| Temporary listeners | IDEA Tomcat used 8080 during browser acceptance; latest check found no listener on 8080, 13307 or the temporary layout-test port 19317. No temporary mysqld or layout browser was left running |
| Post-feedback verification | After the local action-feedback fix, `mvn test` passed 115/115, `mvn -DskipTests package` succeeded, `node --check` and packaged ROOT/`/veriqra` smoke passed. MySQL tests were not rerun while the manual fixture is active because the fixture resets tables |
| Post-Sidebar verification | Real browser layout metrics passed for five long views at desktop 1440×800 and mobile 390×800; `mvn -DskipTests package` and `git diff --check` passed. No JavaScript or MySQL tests were needed for this CSS-only fix |

The first full MySQL run exposed a missing fixed-table allowlist entry in the *new test assertion helper*, not a product error. After correcting that test-only allowlist, the targeted HTTP/MySQL test passed 3/3, then the full run and final package passed 289/289. Existing backend Service/API regressions supplied the business-rule and import-atomicity proof; R4's new assertions specifically expanded Preview no-side-effect coverage.

The earlier CSS-only intermediate WAR hash was `E036AE33F912AEF186062BA5D2AFBC45A61E5A532A694993726FC82D905BC50B`; it is not the final artifact identity. Final Review rebuilt `target/veriqra-0.1.0-SNAPSHOT.war` (5,292,607 bytes) with the MySQL profile and 289 passing tests. Its SHA-256 is `394264DC6686805FB64B0BEFEC578F5E9FDEED42BB750EB258B607711DDDB2CA`. The WAR includes both R4 scripts and no local database credential file.

The repository-external validation directory was renamed from `QATrack-local-validation` to `Veriqra-local-validation`; the current temporary launch/layout scripts were updated. Older review documents retain their historical path evidence.

## Findings and handoff

| Severity | Finding |
| --- | --- |
| CRITICAL | None found. |
| HIGH | None found. |
| MEDIUM | None found in automated review. |
| LOW | Lists are unpaged; assignee selection uses a validated user ID because no member-list API is available; an uncertain Import key is lost on full page refresh or project/view change. |
| INFORMATIONAL | The Preview DTO lists unknown identities without per-result outcome; backend 409 responses hide exact business reasons. R4 business browser acceptance passed; a separate post-fix authenticated check of the localized action feedback was not recorded. |

The R4 implementation and reported business browser acceptance are complete; the Sidebar regression check passed. The focused R4 Final Review records approval and the remaining nonblocking limits. This round performed no commit or push.
