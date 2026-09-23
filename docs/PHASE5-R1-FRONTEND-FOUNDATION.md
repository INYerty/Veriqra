# Phase 5 Round 1 — Frontend Foundation

Date: 2026-09-23. Scope: login, shared frontend infrastructure, application shell, project selection, and project dashboard. The frozen database, DAO, Service, transaction, authentication API, and security filters were not changed. No Hong Kong ECS or development/production data was touched.

## 1. Frontend structure and assets

`src/main/webapp/login.html` and `index.html` are plain server-hosted pages. `assets/js/api.js` is the shared HTTP client; `login.js` and `app.js` own their respective page behavior. `assets/css/app.css` provides restrained responsive styling. `web.xml` explicitly maps Tomcat's default servlet, declares `index.html` as the welcome file, and declares HTML/CSS/JS MIME types. This is necessary in the API-only embedded runtime, which has no global Tomcat `web.xml` MIME defaults.

jQuery 3.7.1 and Bootstrap 5.3.8 compiled CSS/JS are pinned under `assets/vendor/` with upstream MIT licenses. They are served locally, so no CDN connection is needed at runtime. No npm, SPA framework, new Maven dependency, or frontend build pipeline was introduced. Upstream references: [jQuery 3.7.1](https://blog.jquery.com/2023/08/28/jquery-3-7-1-released-reliable-table-row-dimensions/) and [Bootstrap 5.3.8](https://getbootstrap.com/docs/5.3/getting-started/download/).

## 2. Shared API client and error handling

`api.js` resolves `api/...` against the current document URL, so the same scripts work under `/` and `/veriqra/`. It centralizes GET, POST, PUT, DELETE, and action requests. Writes use JSON and exactly `X-Veriqra-Request: 1`; the browser supplies the same-origin cookie and Origin header. The client maps 400, 401, 403, 404, 409, 429 (including numeric Retry-After), and 5xx to safe user-facing messages. Authenticated-page 401 responses redirect to `login.html`. It does not log request bodies, cookies, passwords, or raw server exceptions.

## 3. Authentication and session

The login form has labels, required-field feedback, disabled/loading submit state, generic 401 feedback, and readable 429 feedback. It uses the existing `POST api/auth/login`. A concurrent second submit is blocked. The form never stores the password. On entry, `GET api/auth/me` redirects an already authenticated user to `index.html`.

The application shell stays hidden until `GET api/auth/me` succeeds. Session identity comes only from the server. `POST api/auth/logout` disables its button, clears the optional selected-project UI preference, hides project content, and returns to login. A `pagehide` handler hides the shell before back/forward caching; `pageshow` rechecks the server session when restored. No session ID, password, bearer token, or role is saved in web storage.

## 4. Shell, navigation, and project context

The topbar displays Veriqra, the current username, and logout. The sidebar displays Dashboard and disabled labels for future feature pages; no unfinished page is presented as implemented. The sidebar can be opened on narrower screens. Dashboard is the only functional page in this round.

`GET api/projects` loads projects visible to the authenticated user. Selecting a project verifies it again with `GET api/projects/{id}` before showing details. The selected ID may be kept in `sessionStorage` as a UI preference; it is checked against the fresh list after refresh and never treated as authorization. The server remains responsible for project permissions. Empty lists, missing projects, and changed access have explicit feedback.

The dashboard shows project key/name/description/status, authenticated username, and upcoming workflow areas. It makes one list call and one detail call, without N+1 statistics requests. The current ProjectResponse does **not** expose the user's project membership role. The UI explicitly says that project role is unavailable through the current API; it does not infer TESTER/DEVELOPER from the project list or from `systemRole`. Adding that field is a future backend contract decision, not part of this round. Counts are omitted because no efficient aggregate endpoint exists.

## 5. Responsive, accessibility, and browser safety

The layout supports desktop, tablet, and mobile widths. The sidebar collapses below desktop size; dashboard cards reflow; the login form remains usable on mobile. Inputs have labels, controls have readable names, focus has a visible outline, and errors use alert live regions. No modal is used. Server-provided project names/descriptions and usernames are inserted through `.text()`; no response content is concatenated into `innerHTML`.

Static resources are relative links. No production page hard-codes `/api` or `/veriqra`. The same pages are served from `ROOT.war` and `veriqra.war`. Both contexts use their own server-managed cookie path. Existing strict Origin/Host checks, login limiter, SameSite/HttpOnly/Secure cookie policy, and no-CORS behavior are unchanged.

## 6. Automated validation

The existing `AuthHttpIntegrationTest` now also checks static entry pages, vendor and application CSS/JS, **their response MIME types**, and relative client path/header contract. It executes real login (including a wrong-password response), session cookie path, `auth/me`, project list/detail, and logout under `/veriqra`; the pre-existing ROOT tests cover the same real backend pipeline. The packaged-WAR smoke now checks root and named contexts, static resources and MIME types, protected API, and two independent start/stop cycles for each context. Protected `WEB-INF/web.xml` remains 404.

| Verification | Result |
| --- | --- |
| `mvn test` | 115 passed, 0 failures/errors/skips |
| `mvn -Pmysql-tests test` | 287 passed, 0 failures/errors/skips |
| `mvn -Pmysql-tests clean package` | 287 passed, BUILD SUCCESS |
| JavaScript syntax (`node --check` on three application JS files) | PASS |
| Packaged WAR, ROOT and `/veriqra` | 2 independent start/stop cycles per context, static/API checks PASS |
| Manual browser smoke, IDEA Tomcat `/veriqra_war` | User confirmed login, dashboard/project, refresh, responsive navigation, logout/Back, direct protected-page access, and wrong-password feedback PASS |
| Automated isolated MySQL 8.0.46 | Temporary port 13307; test schema returned to 0 tables and temporary server shut down |
| `git diff --check` | PASS |

The previous full suite had 286 tests; one high-value dual-context HTTP integration test was added. The plain test run excludes MySQL-tagged tests by project configuration, explaining its 115 count. No test connected to `localhost:3306/veriqra`. The pre-Final-Review candidate artifact was `target/veriqra-0.1.0-SNAPSHOT.war`, SHA-256 `1A04A9C7242820054C003C2542E193A9931A04A89CA5BAABB846B1D938B46B74`. The Final Review repackaged it after a small session-resume fix; its current hash is recorded in `PHASE5-R1-FINAL-REVIEW.md`.

## 7. Manual browser smoke

A local browser-smoke WAR server was prepared on loopback with a disposable 13307 schema/user/project. The first browser attempt exposed a real defect: static CSS/JS returned HTTP 200 without MIME types in the embedded runtime and did not execute/render. The three explicit `web.xml` MIME mappings and matching HTTP assertions were added, then the full test/package/WAR smoke gates passed again. Browser control subsequently loaded the local page, but the in-app browser did not provide reliable rendered interaction state; the Edge extension could not attach a current accessibility capture. The disposable schema/account were removed and temporary Tomcat/MySQL stopped.

The user then deployed the packaged WAR to a local IDEA-managed Tomcat 10.1.60 at `/veriqra_war`. A separate `veriqra_test_r1` schema on localhost:3306 received the frozen 19-table schema and a disposable ADMIN/project fixture; the development `veriqra` schema was not modified. In a real browser the user confirmed successful login, dashboard account and project display, refresh persistence, responsive navigation, logout, browser Back and direct protected-page access after logout, and wrong-password feedback. This is **user-reported manual verification**, not an automated browser assertion. The root and `/veriqra` contexts remain covered by packaged-WAR HTTP smoke tests; the manually exercised IDEA context was `/veriqra_war`. After testing, the user stopped Tomcat; ports 8080, 19099, and 13307 had no listener. The disposable test schema returned to **0 tables**.

## 8. Known limits and next round

CRITICAL/HIGH: none found. MEDIUM product gap: the current API omits project membership role, so the dashboard cannot show a real TESTER/DEVELOPER badge. LOW: no aggregate statistics and no R2 feature pages are implemented. INFORMATIONAL: the missing MIME mapping found during browser validation was fixed and protected by automated checks; real-browser interaction was subsequently confirmed by the user. The frontend foundation is ready for targeted final review. Round 2 may add Requirements, Test Cases, Steps, and Traceability UI after that review; none is implemented here.
