# Veriqra Phase 4 Round 4 - Automation and JUnit Import API

## Scope

Round 4 adds the HTTP adapter for the existing `AutomationService` and
`TestImportService`. It does not change the database schema, persistence
contracts, transaction manager, or the frozen execution history model.

## Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| GET | `/api/projects/{projectId}/automation/identities` | List identities in a project |
| POST | `/api/projects/{projectId}/automation/identities` | Register or return an existing identity |
| GET | `/api/projects/{projectId}/automation/mappings` | List mappings in a project |
| GET | `/api/automation/identities/{identityId}` | Read an identity |
| GET | `/api/automation/identities/{identityId}/mapping` | Read the current active mapping |
| PUT | `/api/automation/identities/{identityId}/mapping` | Confirm or rebind a mapping |
| POST | `/api/automation/identities/{identityId}/mapping/deactivate` | Deactivate a mapping |
| POST | `/api/projects/{projectId}/imports/preview` | Parse and resolve a JUnit report without importing |
| POST | `/api/projects/{projectId}/imports` | Perform an atomic JUnit import |

Automation writes use strict JSON and reject unknown fields. XML routes require
`application/xml` (parameters are accepted), read at most 5 MiB, and pass the
exact bytes to `JUnitXmlParser`. Preview returns mapped results, unknown
identities, and entry issues. It creates no identities, test cases, runs,
attempts, or import records.

Formal import requires `requestKey`, `sourceNamespace`, `originalFilename`, and
`runName` query parameters; `environment` and `buildVersion` are optional. A
new import returns `201`, while an equivalent request-key replay returns `200`
with `replayed: true`. The response contains the import record, completed run,
snapshots, and attempts. The `Location` header points to the existing run
resource. A request-key reuse with different bytes or metadata returns `409`.

## Authorization and security

All state-changing routes pass the existing strict Origin gate and require
`X-Veriqra-Request: 1`, an authenticated session, and the Service's project
authorization. Developers can read and preview according to project access;
identity registration, mapping, and formal import require the Service's write
permission. The HTTP layer does not trust project IDs, roles, or identity
ownership supplied by a client. Service checks enforce same-project mappings
and active/READY target cases.

JUnit parsing retains the existing security policy: DTDs, entities, external
DTD/schema access, and external resources are disabled; an entity resolver
rejects attempts explicitly. `<failure>` and `<error>` become `FAIL`, with
the existing `[JUnit error]` marker for errors; `<skipped>` remains `SKIPPED`.

## Atomicity and history

The formal import delegates to `DefaultTestImportService`, which performs the
run, immutable run-case and step snapshots, attempts, and import record in one
outer transaction. A failure rolls the entire unit back. Attempts remain
append-only and request-key/submission-key uniqueness remains enforced by the
existing Service and database design.

## Verification

- Existing Service and parser tests cover preview side effects, mapping
  lifecycle, all JUnit outcomes, replay/conflict behavior, XML security, and
  injected transaction rollback.
- `AutomationImportHttpIntegrationTest` adds the intended HTTP -> Service ->
  MySQL coverage. It requires the isolated `veriqra_test_*` schema only.
- The Java NIO loopback issue was resolved before final verification. Embedded
  Tomcat and `java.net.http.HttpClient` tests completed successfully.
- `mvn test`: 286 tests passed.
- `mvn -Pmysql-tests test`: 286 tests passed against the isolated MySQL test
  schema.
- `mvn -Pmysql-tests clean package`: 286 tests passed and the WAR build
  succeeded.
- `AutomationImportHttpIntegrationTest`: 3 tests passed.
- The final WAR is `target/veriqra-0.1.0-SNAPSHOT.war` with SHA-256
  `c1c17032b1c4ef5525963fc54b59918e74c7d28be691d783d6dc43db416efc25`.
- The isolated test schema was left with zero tables and port 13307 has no
  listener after cleanup.

## Findings

### CRITICAL

None in the Round 4 implementation.

### HIGH

None found after the final HTTP and MySQL integration verification.

### MEDIUM

None in the Service or persistence behavior reviewed here.

### LOW

No separate import-history listing endpoint was added because the existing
`TestImportService` exposes formal import results only. Run resources remain
the source for later history/detail reads.

### INFORMATIONAL

CSRF remains the existing same-origin plus request-marker model; no CORS was
added. Login rate limiting, trusted proxy configuration, secure cookies, and
SameSite policy remain as established in earlier rounds. Frontend work and
any future import-history UX belong to later phases.

## Decision

The HTTP adapter and contracts are implemented and the automated HTTP/MySQL
verification passed. No production data, ECS host, or application schema was
touched. Standalone WAR context smoke verification remains a deployment check
to perform before publishing a deployment artifact if it has not already been
run for this exact build.
