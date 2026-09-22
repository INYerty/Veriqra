# QATrack Public Deployment Security Final Review

> Historical report: the original identifiers, paths, configuration names and checksums below are preserved as recorded. For current Veriqra configuration and deployment, see [VERIQRA-RENAME.md](VERIQRA-RENAME.md).

Review date: 2026-09-21

Scope: Phase 4 Deployment Hardening only

Reviewed baseline: `main` / `b920702a0d03` plus the current uncommitted hardening diff

Decision: **APPROVED for commit and for use as the code baseline of a single-instance, same-origin HTTPS deployment. Public exposure remains blocked by the deployment checks in section 21.**

## 1. Final verdict

The application-side hardening is approved. No CRITICAL, HIGH, or blocking MEDIUM issue remains. Final Review found one blocking MEDIUM timing-hardening discrepancy: the missing-user dummy PBKDF2 used 210000 iterations while the documented production bootstrap baseline uses 600000. The dummy cost is now 600000, the write-route Origin test covers every current route family and action, and the complete validation matrix passed again.

| Question | Answer |
| --- | --- |
| A. Public Deployment Security code APPROVED? | **Yes.** Approved for the stated single-instance, same-origin HTTPS model. |
| B. Commit 前 code blocker? | **None.** The only blocking MEDIUM found in this review was fixed and revalidated. |
| C. Can commit/push? | **Yes**, after reviewing the current diff. This review did not commit or push. |
| D. Formal public-version baseline? | **Yes as a code baseline.** It is not yet authorization to expose the ECS publicly. |
| E. What remains on Hong Kong ECS? | Final domain/DNS and certificate; HTTP→HTTPS; environment gates; Nginx header overwrite; loopback-only Tomcat proxy trust; network restrictions; spoofed-forwarded-header tests; HTTPS cookie/session/limiter/API smoke; backup, logging and rollback checks. |
| F. Is the CSRF strategy sufficient? | **Yes for the current same-origin application**, provided the fixed HTTPS Origin and trusted proxy chain are configured. It combines required Origin equality, Host consistency, a non-simple custom header and SameSite=Lax. |
| G. Is the limiter sufficient? | **Yes for a single-instance course project.** It is intentionally local memory and is not distributed DDoS protection. |
| H. Is the trusted-proxy model sound? | **Yes conditionally.** Nginx must overwrite/clear forwarded headers and Tomcat must trust only the actual loopback proxy. |
| I. May the new WAR be deployed to Hong Kong after commit/push? | **Yes, for restricted final public-security acceptance.** Keep public restrictions until section 21 passes. |
| J. May Round 4 start after public acceptance? | **Yes.** First finish and record the ECS acceptance; this review did not start Round 4. |

Severity summary:

- **CRITICAL:** none.
- **HIGH:** none open.
- **MEDIUM:** one CODE issue found and resolved: dummy PBKDF2 work factor mismatch. Deployment gates remain mandatory but are not code defects.
- **LOW:** no absolute session lifetime or global session revocation; limiter state is per-process and restart-volatile; shared-IP quotas can affect availability.
- **INFORMATIONAL:** malformed JSON/media requests fail before the password-attempt limiter; edge request-rate and header-size controls remain deployment responsibilities.

## 2. Threat model

The reviewed controls address password guessing, concurrent threshold bypass, random-key memory pressure, login CSRF, authenticated write CSRF, Host poisoning, forwarded-header spoofing, insecure session cookies, session fixation, username-state response differences, and secret leakage through application errors or logs.

The accepted deployment model is one QATrack WAR behind one trusted Nginx reverse proxy, one Tomcat instance and same-origin browser clients over HTTPS. Distributed denial of service, a compromised reverse proxy/container/host/database, cross-site scripting, multi-instance shared quotas and full security monitoring are outside the application control implemented here. XSS would undermine same-origin browser defenses, so later frontend work must retain output encoding and adopt a reviewed CSP.

## 3. Rate limiter correctness

`LoginRateLimiter` implements a **fixed five-minute window**, not a sliding window. The first tracked event starts the window. Expired buckets reset or are removed; no permanent lockout occurs.

The pair quota admits at most five concurrent-or-failed password checks for one normalized IP/username pair; the sixth is rejected. The IP quota admits at most thirty across usernames; the thirty-first is rejected. `Limited` is the only path mapped to 429. `Retry-After` is the ceiling of the remaining fixed window in seconds, with a minimum of one second; capacity saturation also returns one second.

A successful login clears only the matching pair failure count. It retains whole-IP failures and does not affect other usernames. `AuthenticationException` covers nonexistent users, bad passwords and disabled users, so each is counted uniformly. Username normalization lowercases ASCII and removes trailing spaces, matching the service's printable-ASCII constraint and the relevant database comparison behavior. Null/overlong usernames use one bounded invalid key; blank/trailing-space variants do not create unbounded state. Remote-address length is bounded.

JSON parsing, media-type validation and body-size enforcement occur before `acquire`; malformed requests therefore do not consume password-failure quota. This is deliberate: the limiter bounds authentication work, while Nginx/Tomcat must bound malformed-request traffic. The 64 KiB JSON body limit remains in effect.

## 4. Rate limiter concurrency

All bucket reads, capacity checks, reservation increments and Permit completion transitions synchronize on the limiter instance. `failures + pending` is checked before admission, so concurrent requests near a threshold cannot all pass. A Permit completion is idempotent: success, failure or `close()` can win only once, preventing double decrement and negative pending counts.

The try-with-resources call in `ApiServlet` releases a reservation on parsing-independent runtime failures. Authentication failure converts it to one pair and one IP failure. Success clears the pair and preserves IP history. Interleaving successes and failures does not corrupt counters.

`LoginRateLimiterTest.concurrentAcquisitionCannotExceedPairBudget` starts eight real executor tasks behind a shared latch, holds the two admitted permits with a second latch, and proves only two enter when the configured test quota is two. This is genuine overlap, not sequential future retrieval. The test supports threshold atomicity; it is not a universal deadlock or performance proof.

## 5. Memory bound

The map has a hard 4096-bucket cap. A request can require two entries, and the capacity check is atomic with insertion. Expired buckets with no pending request are reclaimed on each acquire. Pending buckets are retained until their Permit completes.

At saturation the limiter fails closed with 429 and cannot grow further. Random IP/username input therefore cannot make the map unbounded. The tradeoff is availability: a key-space flood or many users behind one NAT may temporarily reject legitimate login attempts. This is acceptable for the current single-instance course deployment and is documented; edge rate limiting remains recommended.

## 6. Origin validation

`SameOriginPolicy` parses origin components with `URI` and compares a value object containing scheme, normalized lowercase host and normalized port. Default HTTP/HTTPS ports become 80/443. Paths, trailing slash, query, fragment, userinfo, wildcard, port zero/out-of-range, dangling colon, unsupported/mixed-case schemes, malformed values and strings over 512 characters are rejected.

The check requires exactly one Host and, on state-changing requests, exactly one Origin. Missing Origin, literal `null`, duplicate/comma-combined values and attacker suffixes such as `qatrack.example.com.evil.com` fail exact equality. There is no `startsWith`, `contains` or substring host check.

A configured public origin must be one explicit HTTPS origin. Invalid `QATRACK_PUBLIC_ORIGIN` fails application initialization; it cannot silently fall back to development mode.

## 7. Development fallback

Without `QATRACK_PUBLIC_ORIGIN`, the target must be HTTP, the validated target host must be an explicit loopback literal, and the socket-derived `request.getRemoteAddr()` must also be loopback. `localhost.evil.com`, `127.0.0.1.nip.io`, arbitrary Host values and public remote addresses fail.

The application ignores raw `X-Forwarded-Host`, `X-Forwarded-Proto`, `X-Forwarded-For` and `Forwarded`. A remote client therefore cannot select development mode using those headers. Host must also exactly match the container-normalized scheme/serverName/serverPort tuple.

## 8. State-changing coverage

`AuthenticationFilter` is mapped to `/api/*`, runs Origin policy before the authentication exemption for login, and classifies every method other than GET, HEAD and OPTIONS as state-changing. POST, PUT, PATCH and DELETE therefore pass the same Origin/custom-header gate before route or service logic.

The real-container test enumerates login, logout, projects, requirements and updates, traceability attach/confirm/remove, test-case create/update, plan create/update/archive/add/remove, run create/complete/cancel, attempt append, defect create/update/actions and evidence add/remove. It sends all four write verbs to every listed path with an evil Origin and verifies 403 before business logic.

Current GET handlers are read-only. HEAD follows the container's safe retrieval semantics, and OPTIONS receives no CORS grant and has no business side effect. Future write endpoints remain protected by the `/api/*` filter mapping, but their tests should still add a representative route.

## 9. Login/logout CSRF

`POST /api/auth/login` is exempt only from requiring an existing session. It is not exempt from Origin, Host or custom-header checks, so cross-site login/session-confusion requests are rejected before credentials are processed. `POST /api/auth/logout` requires both the same-origin gate and an authenticated session. Ordinary cross-site form submissions cannot supply the required custom header, and cross-origin script requests receive no CORS authorization.

## 10. Custom header

Every state-changing request must contain exactly one `X-QATrack-Request` header whose value is exactly `1`. Missing, wrong, duplicated or comma-combined values are rejected. HTTP header-name matching is container case-insensitive; value matching is intentionally exact.

The header is one part of the combined defense. The design also requires strict Origin, Host/effective-origin agreement, SameSite=Lax and no broad CORS. It is not described as a standalone CSRF token or synchronizer-token implementation.

The application emits no `Access-Control-Allow-Origin` or credentialed CORS response. In particular, there is no wildcard-origin plus credentials combination.

## 11. Cookie

`SessionCookiePolicy` configures the real ServletContext session cookie as HttpOnly, SameSite=Lax and Path equal to the application context (`/qatrack` in deployment). It sets the actual `SessionCookieConfig.secure` flag from `QATRACK_SESSION_SECURE`; the embedded Tomcat test proves both the Secure production branch and non-Secure local branch in real `Set-Cookie` output.

An HTTPS `QATRACK_PUBLIC_ORIGIN` cannot start unless `QATRACK_SESSION_SECURE` is exactly `true`. Invalid secure values fail startup. Cookie Domain remains unset, producing a host-only cookie. The deployment must still verify the final HTTPS response header through Nginx.

## 12. Session security

Successful login invalidates the old session and creates a new session identity. Logout invalidates it. A failed login does not call session login/rotation and cannot replace an existing authenticated identity. Only the actor ID is stored; roles and membership are not cached in the session.

The 30-minute idle timeout is applied through `ServletContext.setSessionTimeout(30)` and retained in `web.xml`. Protected requests recheck that the user is ACTIVE; loss of active identity invalidates the session. There is no absolute lifetime or global revoke registry. Those are LOW/FUTURE items for this course scope.

## 13. Enumeration

Nonexistent username, wrong password and disabled user return the same 401 status, stable error code and generic body. Limiter keys and behavior do not query account existence, and 429 exposes neither username, IP normalization, remaining attempts nor bucket counts.

`PasswordVerifier` performs a dummy PBKDF2 derivation for absent/invalid hashes. Final Review aligned its dummy cost to the current 600000-iteration production bootstrap recommendation. The implementation correctly does not claim strict constant-time network behavior: stored legacy hashes may use another accepted cost, scheduling and database lookup add noise, and exact response latency is not an API guarantee.

`ApiExceptionFilter` maps Origin/security rejection to 403 and limiter rejection to safe JSON 429 with `Retry-After`. It does not expose bucket keys, remaining attempts, normalized IP or account existence. If a response is already committed, it does not reset or write a second response. Other error paths retain the reviewed generic mappings rather than converting expected security failures to 500.

## 14. Trusted proxy

The limiter uses `request.getRemoteAddr()` only. Application production code does not read raw forwarding headers. Behind Nginx, that address represents the real client only after Tomcat's trusted-proxy processing; without it all proxy traffic can appear as loopback and the 30-failure IP quota can become a shared availability limit.

This is a **DEPLOYMENT BLOCKER**. It is safe only when Tomcat's RemoteIpValve trusts the actual loopback proxy source and Nginx is the sole reachable upstream. The application dependency on normalized request attributes is explicit and test requests with forged forwarding headers do not change security decisions.

## 15. Nginx/Tomcat boundary

The reviewed deployment guide requires Nginx `proxy_set_header` overwrite semantics for Host, X-Real-IP, X-Forwarded-For and X-Forwarded-Proto, and clears unneeded Forwarded/X-Forwarded-Host input. Tomcat then accepts proxy metadata only from loopback/the exact proxy, restoring remoteAddr, scheme, secure and serverPort. A catch-all `internalProxies=.*` is prohibited.

Tomcat remains loopback-only and is not directly exposed. Nginx rejects unknown Host values. The application verifies Host against normalized serverName/serverPort and the fixed public origin. `QATRACK_SESSION_SECURE=true` is still explicit and does not depend on a forwarded header.

## 16. HTTPS/HSTS

Production requires a valid certificate for the final domain and a 301/308 redirect from port 80 to HTTPS. Login and API traffic must not remain available over public plaintext HTTP. Public restrictions remain in place until the HTTPS path is tested end to end.

Nginx owns HSTS. Enable it only after stable HTTPS validation; do not begin with a long preload/includeSubDomains policy. Nginx also owns frame protection and Referrer-Policy. The Java API already emits `X-Content-Type-Options: nosniff`; avoid conflicting duplicate header ownership. Final CSP waits for the frontend's resource model.

## 17. Logging

Production code does not log passwords, password hashes, login bodies, Cookie/JSESSIONID, Authorization or raw rejected Origin. Expected 401/403/429 paths are not logged with attacker-controlled header values. The 500 path logs exception type and stack frames but omits exception messages and causes, then returns a generic body.

Nginx/Tomcat access logs must likewise omit request bodies, Cookie, Authorization and session IDs. Security-event logging can be added later with bounded/sanitized fields, retention and access controls. Request/header size remains a Nginx/Tomcat responsibility; the application continues to enforce the existing 64 KiB JSON body limit.

## 18. Lifecycle

The limiter is an instance field of `ApiServlet`, contains only a synchronized bounded map and has no Timer, executor, thread or static application registry. WAR undeploy therefore has no limiter shutdown task or classloader thread leak.

`ApplicationListener` retains ownership of the pool and Connector/J drivers created by the web application. On failed startup or context destruction it closes the pool, invokes Connector/J cleanup shutdown for its driver and deregisters only owned drivers. Two independent standalone WAR deployment/stop cycles passed.

No default/startup administrator, password, hash or public bootstrap endpoint was introduced. ADMIN creation remains a one-time restricted deployment procedure, and production must not run `seed.sql`.

No security/cache framework or new runtime dependency was added. The implementation remains JDK + Servlet + Jackson with the existing Connector/J runtime; it does not introduce Spring Security, Redis, Guava Cache or background cleanup infrastructure.

## 19. Test quality

The 17 hardening tests cover fixed-window threshold/recovery, pair and IP behavior, success cleanup, IP isolation, genuine concurrent reservation, capacity fail-closed/reclamation, abort/double-close, Origin canonicalization and malformed/multiple values, loopback fallback and spoofed forwarded headers, config fail-safe, real write-route gating, 429/Retry-After secrecy, and real cookie attributes. The route enumeration was extended in this review to cover traceability and all current TestCase/TestPlan write actions.

Final evidence after the review fix:

| Validation | Result |
| --- | --- |
| `mvn test` | 111/111 PASS |
| `mvn -Pmysql-tests test` | 279/279 PASS |
| `mvn -Pmysql-tests clean package` | 279/279 PASS; BUILD SUCCESS |
| Standalone WAR | Two independent deploy/stop cycles PASS; protected API returns 401 JSON |
| WAR content | Security classes present; only Jackson and Connector/J runtime jars; no test classes, local config, Servlet API or embedded Tomcat |
| WAR SHA-256 | `F3B93D055E1EFBE1E3279CE42D3F20F666C5A59ACACE6941E742B69AE5CC382F` |
| Isolated MySQL | 8.0.46 on 13307 only; test schema 0 tables after tests; normal shutdown; no 13307 listener |
| `git diff --check` | PASS |

The validation did not connect to `localhost:3306/qatrack` or ECS. The Windows validation process used a repository-external long TEMP/TMP path to avoid a local JDK/8.3-path selector failure; this changed no project or machine configuration.

## 20. Code blockers

**CODE BLOCKER: none open.**

| Severity | Finding | Disposition |
| --- | --- | --- |
| CRITICAL | none | — |
| HIGH | none | — |
| MEDIUM | Missing-user dummy PBKDF2 cost was 210000 while the current production hash baseline is 600000, permitting a more measurable timing class difference. | **Resolved:** dummy cost set to 600000; all validations rerun and passed. |
| LOW | No absolute session timeout/global revoke; limiter is restart-volatile and shared-NAT sensitive. | Accepted within current scope; section 22. |
| INFORMATIONAL | Malformed requests are rejected before limiter acquisition; header/request flood protection is at the edge. Compiler emits existing deprecation/optional Tomcat reflection warnings. | Semantics documented; no correctness failure. |

No schema, seed, Maven dependency, ConnectionPool, transaction manager, Phase 3 business rule, `web.xml` or AuthService contract changed.

## 21. Deployment blockers

**DEPLOYMENT BLOCKER: open until every item below is verified on the Hong Kong ECS.** These are not satisfied by local tests.

- Fix the final domain and DNS; install and verify its TLS certificate.
- Redirect HTTP 80 to HTTPS with 301/308 and reject unknown Host values.
- Set one exact `QATRACK_PUBLIC_ORIGIN=https://<final-domain>` and `QATRACK_SESSION_SECURE=true`; prove startup fails for bad/missing paired configuration.
- Configure Nginx to overwrite/clear forwarding headers and proxy only to loopback Tomcat.
- Configure Tomcat RemoteIpValve to trust only the expected loopback proxy and verify normalized remoteAddr, scheme, secure and serverPort.
- Keep Tomcat 8080 and MySQL 3306 off the public network; verify security group, host firewall and IPv6 exposure.
- From an external client, forge X-Forwarded-For/Proto/Host/Forwarded and prove they cannot alter client IP, origin or scheme decisions.
- Through the real HTTPS endpoint, verify Secure/HttpOnly/SameSite=Lax/Path, login rotation, logout invalidation, Origin/custom-header rejection, limiter threshold, 429/Retry-After and recovery.
- Run the core API/persistence/redeploy smoke, inspect logs for secrets/unexpected 500s, and verify backups, rollback, monitoring and log rotation.
- Record the deployed commit, WAR checksum and acceptance evidence before removing existing public restrictions.

`docs/CLOUD-DEPLOYMENT-HANDOFF.md` is explicitly marked as a historic smoke-deployment snapshot. Operators must use `docs/PUBLIC-DEPLOYMENT-SECURITY.md` and this Final Review for the new WAR and must not reuse the old checksum or the old pre-limiter security conclusion.

## 22. Future enhancements

**FUTURE ENHANCEMENT:** absolute session lifetime; global session revocation after credential/security changes; persistent or distributed limiter for multiple instances; edge/distributed DDoS protection; finalized frontend CSP; request/correlation IDs; sanitized security-event metrics and alerting; more complete operational rate controls.

These do not block the current single-instance course deployment. Revisit them before multi-instance scaling, third-party origins, mobile/API-token clients or higher-risk production use.

## 23. Git status

At final review time:

- branch: `main`
- `HEAD`: `b920702a0d03`
- `origin/main`: `b920702a0d03`
- staged: 0
- tracked modified: 13
- untracked files: 9 after adding this review
- `git diff --check`: PASS
- commit/push: not performed

The dirty working tree is the complete Deployment Hardening change set plus this review. Review the full diff, then one commit may be created and pushed before the restricted ECS acceptance deployment.
