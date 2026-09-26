# Phase 5 R5.1 — English / 简体中文 interface

R5.1 changes presentation resources only. It does not change database values, Java enums, REST paths or fields, authentication, authorization, Credit accounting, or server-side error codes.

## Supported locales and selection

The UI supports `en` and `zh-CN`. On initialization, `assets/js/i18n.js` uses the valid `veriqra.locale` value in `localStorage`, then the browser's preferred language, then `en`. Browser languages beginning with `zh`, `zh-CN`, `zh-SG`, or `zh-Hans` select `zh-CN`; unsupported languages select `en`. The language selector is present on Login, the application shell, and every Administration page. Choosing a language updates the current page without a full reload and persists only in that browser. No user-profile field or server session value is added.

`<html lang>` and page titles follow the selected locale. The script derives language-pack URLs from its own script URL, so `/i18n/*.json` resolves correctly for both ROOT and `/veriqra` deployments. HTML contains readable English fallback. If a language pack fails to load, the UI keeps the available English text and remains usable.

## Translation architecture

`src/main/webapp/i18n/en.json` and `zh-CN.json` contain the same flat, namespaced keys (`common.*`, `shell.*`, `auth.*`, `assets.*`, `execution.*`, `defects.*`, `automation.*`, `admin.*`, `status.*`, `audit.*`, `errors.*`, `http.*`). English uses the same lookup path as Chinese. Static content uses `data-i18n`, `data-i18n-placeholder`, `data-i18n-title`, or `data-i18n-aria-label`. Dynamic components call `I18n.t(key, params, EnglishFallback)` and rerender on `veriqra:localechange`. Interpolation inserts plain text, never HTML; dynamic user data continues through `textContent` or jQuery `.text()`.

For a new UI string, add one stable namespaced key to **both** JSON files, add an English fallback to the markup or dynamic `I18n.t` call, and run the resource parity test. Translate only the surrounding system text. Names, descriptions, file names, free-text audit summaries, automation identities, URLs, request IDs, HTTP methods, and other user or technical data retain their original values. The eight exact, server-generated audit summary phrases have UI translations; unknown/free-text summaries are displayed unchanged.

`I18n.enumLabel` translates displayed state, role, device, result, Credit transaction type, and audit action labels. Its input remains the original machine value, such as `NEEDS_REVIEW`, `GRANT`, or `RATE_LIMITED`. The API still sends and receives those values. Known backend error codes map to UI translation keys; unknown codes use the safe existing generic HTTP message. `Intl.DateTimeFormat` renders UTC timestamps in `Asia/Shanghai` without changing API timestamps. `Intl.NumberFormat` formats counts. Credit decimal strings pass through `BigInt` before locale formatting, preserving values beyond `Number.MAX_SAFE_INTEGER`.

## Coverage and switching behavior

- Login, application topbar/sidebar, Dashboard, Projects, Requirements, Test Cases, Steps, Traceability, Plans, Runs, snapshots, Attempts, Defects, Automation Mapping, JUnit preview/import, and all nine Administration pages use the shared catalogs.
- Dynamic tables, cards, badges, loading/empty/error states, form validation, confirmation prompts, filter choices, pagination, and modal labels use translated display text.
- Language switching rerenders the active workspace and Administration data. It preserves current project and filter/page state. An open Administration modal closes safely and can be reopened in the new language. An unsubmitted Defect action form closes before the translated detail refresh; no action is sent. Test-asset and execution forms preserve entered values. Import file, preview, request-key state, and completed result remain intact.
- The `Experimental Credits` label is `实验性额度` in Chinese. The UI states that this internal feature has no monetary value or consumption feature; it is never called a wallet, currency, or asset.

## Timestamp and Today semantics

The existing MySQL `DATETIME(6)` values are UTC wall-clock values written and read through a JDBC session set to UTC. Java maps them to `LocalDateTime`, and JSON emits an offset-free ISO timestamp. The application `serverTime` is an explicit `Instant` with `Z`. Previously, a browser interpreted the offset-free UTC timestamps in its own time zone, producing inconsistent display. `I18n.formatDate` and `formatDateTime` now interpret those existing values as UTC and render them with the IANA `Asia/Shanghai` zone; explicit offsets remain authoritative. Neither stored values nor API fields changed. Administration's date/time filters convert Shanghai wall-clock input back to the UTC values expected by the existing API.

`AdminTodayWindow` computes `[00:00 today, 00:00 next day)` in `Asia/Shanghai`, converts the bounds to UTC `LocalDateTime`, and supplies them to all nine Administration Today metric subqueries. This avoids both the former `UTC_DATE()` boundary and dependence on the host JVM zone. The System page names the display zone and formats `serverTime` in it. Unit and real-MySQL tests cover 2026-09-25 17:00 UTC falling on September 26 in Shanghai, including a run with a New York JVM zone.

## Authentication UX

An unauthenticated `GET /api/auth/me` returning 401 is the normal login-page state, including after logout and on repeated visits. Wrong-password login 401 shows the localized invalid-credentials message. Login 429 uses the server's `Retry-After` seconds to show a localized wait duration without interpreting it as a timestamp; other failures retain their existing HTTP/error-code mapping. The Origin, Host, request-marker, cookie and limiter policies are unchanged.

## Validation and browser acceptance

`I18nResourcesTest` checks strict JSON parsing, matching keys and placeholders, static page/module inclusion, valid HTML markers, literal JavaScript keys, and stable option values. The Node tests in `src/test/js` cover locale priority, persistence, interpolation, BigInt Credits, UTC-to-Shanghai and cross-day display, retry delay, `auth/me` 401, wrong-password 401, login 429, pack failure, and ROOT/context-relative URLs. `WarDeploymentSmoke` checks both JSON packs and `i18n.js` in ROOT and `/veriqra` deployments; `WEB-INF/web.xml` explicitly serves `.json` as `application/json`.

The real browser pass used only a disposable loopback Tomcat and owned `veriqra_test_r1` fixture. English/Chinese login, logout, repeated `auth/me` 401, wrong-password 401, actual limiter 429 with `Retry-After`, language persistence, Dashboard and business workspaces, and all nine Administration pages were checked. User Edit, Grant in both languages, Reclaim, overbalance Reclaim error, and a native-confirmed Batch Grant were submitted; balances, ledgers, batch ID, audit and localized success/error feedback were inspected. The user personally submitted Create User and Reset Password and reported that the old password failed while the new one worked; no new password was shared. In a final restored browser session, the user submitted an existing username in the Create User form and reported that both `zh-CN` and English showed the expected localized error, with no raw `USERNAME_CONFLICT`. Login History, Access Logs and Audit Log filter/apply/clear/no-result flows and six independent paginated lists were exercised. A Shanghai time filter selected the expected audit events. The ordinary USER admin denial, invalid Credit amount, audit-summary translation, desktop/narrow layout, hamburger and modal footer were checked in the final browser candidate.

The browser pass exposed and fixed three presentation issues: known server-generated audit summaries stayed English, native form validation used the browser's language instead of the chosen UI language, and switching locale on an ordinary USER's forbidden Administration page left its old-language message. The final candidate was retested for all three. Unknown/free-text audit summaries remain unmodified.

Final verification on 2026-09-26: `mvn test` 121/121 PASS; `mvn -Pmysql-tests test` 315/315 PASS; `mvn -Pmysql-tests clean package` 315/315 PASS with WAR BUILD SUCCESS; Node frontend tests 8/8 PASS; JavaScript syntax PASS; catalog keys and placeholders match at 599 per locale; static inventory found only brand/technical values and fallback text; `git diff --check` PASS. The standalone WAR smoke passed ROOT and `/veriqra`, two independent start/stop cycles each, including JSON MIME, static resources and protected API. WAR inspection found required resources and no local configuration/test dependencies. The final WAR is `target/veriqra-0.1.0-SNAPSHOT.war` (5,419,640 bytes), SHA-256 `22B61C77A15920A982B253F6ECA8D89BB002922CA9B45436EDBD9F95AFD7235F`. Rebuilding invalidates this hash.

All browser Tomcat hosts stopped. The owned test schema has zero tables and no ownership marker. Validation ports 1841, 5275, 7184 and 13307 have no listener. No production host or database was accessed. The requested R5.1 acceptance items are complete; source freeze and local commit/push are ready, while production deployment remains a separate user-controlled operation.

## Known limitations

Only two locales are shipped; the preference is browser-local and is not synchronized across devices. Plural rules use simple count templates rather than ICU MessageFormat. Server-owned user data and safe fallback error messages are not automatically translated. No frontend build pipeline or third-party i18n framework is required.
