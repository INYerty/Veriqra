# Collaboration V1 Final Review

Review date: 2026-09-27. Scope: project managers, teams, team leads, task assignment, task lifecycle, REST API, browser UI, and the additive 24-to-29-table migration. This review did not deploy or change any development or production database.

## Verdict and findings

**PASS WITH FIXES.** No Critical or High findings remain. Collaboration V1 source is ready to freeze after the two Medium UI findings below were corrected and the final WAR was retested.

| Severity | Finding | Resolution / evidence |
| --- | --- | --- |
| Critical | None | Cross-project writes/reads, cross-team writes, self-acceptance, and platform account access were rejected by real HTTP tests. |
| High | None | Disabled/inactive/revoked actors lost write access; task state and event writes share a transaction. |
| Medium | Membership state appeared as raw `ACTIVE`/`INACTIVE` in the collaboration UI. | Added `collab.membership.*` keys and localized the three member/manager views. Verified in both languages in the final WAR. |
| Medium | Changing language left open team/task detail and event history in the previous language. | Re-rendered selected details on `veriqra:localechange`; verified zh-CN → English → zh-CN in a real browser on the final WAR. |
| Low | Task lists are loaded without pagination, and lead checks on a list can cause repeated queries. | No correctness defect at V1 scale; measure before adding pagination/query optimization. |
| Informational | An active project member can read the project team list and a team's roster even if their own team membership is inactive. | Current read scope is project-wide; team management and task writes still require active scoped responsibility. Narrow roster visibility later if product privacy requirements demand it. |

The review also disabled every action button in the selected task action group while a transition request is pending. This prevents a second, different transition from being sent by a rapid click; the backend version check remains the authority.

## Permission model and matrix

`users.system_role` remains `ADMIN` or `USER` for platform duties. `project_managers` and `project_teams.lead_user_id` express separate project responsibilities. `project_members.project_role` remains the professional identity (`TESTER` or `DEVELOPER`), not an authorization shortcut.

| Operation | Platform ADMIN | Project manager | Own-team lead | Ordinary project member |
| --- | --- | --- | --- | --- |
| Create/disable platform user, reset password, set system role | Yes | No | No | No |
| Create project | Yes | No | No | No |
| Appoint/revoke project manager | Yes, for an active member | No | No | No |
| Add an existing account to project | Yes | Own project | No | No |
| Create team / change lead | Only if separately appointed manager | Own project | No | No |
| Add/remove team member | Only with project responsibility | Own project | Own team | No |
| Create/reassign task | Only with project responsibility | Own project | Own team | No |
| Start/submit task | If the assignee and active member | If the assignee | If the assignee | If the assignee |
| Accept/return task | Only with project responsibility and not the assignee | Own project, not own task | Own team, not own task | No |
| Read another project’s collaboration API | Only under existing platform project-read policy | No | No | No |

Platform ADMIN alone cannot create teams or operate tasks; an explicit active project responsibility is required. Manager A’s direct HTTP `GET/POST /api/projects/2/teams`, `GET /api/projects/2/tasks`, and `GET /api/admin/users` all returned 403. The team lead’s attempted write to another team and another project returned 403. An ordinary member’s team creation and own-task acceptance returned 403.

## Real task state machine

`OPEN → IN_PROGRESS` (assignee starts), `IN_PROGRESS → SUBMITTED` (assignee submits), `SUBMITTED → ACCEPTED` (independent manager/lead accepts), and `SUBMITTED → IN_PROGRESS` (independent reviewer returns with a required note). A manager/lead may cancel `OPEN` or `IN_PROGRESS`; reassignment is limited to those two states. `ACCEPTED` and `CANCELLED` are terminal. There is no Claim endpoint, `ASSIGNED` state, separate `REJECTED` state, or Credit reward in V1; return is the supported rejection-for-changes path.

Task changes append `CREATED`, `STARTED`, `SUBMITTED`, `ACCEPTED`, `RETURNED`, `CANCELLED`, or `REASSIGNED` events as applicable. Actor, old status/assignee, note, and timestamp are persisted. The DAO has no ordinary event update/delete method. The browser verified `CREATED → STARTED → SUBMITTED → RETURNED → SUBMITTED → ACCEPTED` with the correct actors and localized timestamps.

## Transaction, concurrency, and security review

The Service re-reads active user, project membership, manager/lead appointment, group membership, task project, status, and version in the transaction. DAO methods use prepared statements and do not commit, roll back, or close the caller's connection. Task state update and event append are in the same outer transaction. Team/task versions reject stale writes; a competing terminal-transition test produced one winner and one conflict, without a second terminal event. This test is evidence against silent lost update in that case, not a proof that arbitrary workloads cannot deadlock.

Project/team/task identity is checked server-side, with composite database FKs also rejecting cross-project associations. The REST layer continues through the existing session, Origin and `X-Veriqra-Request: 1` checks. Disabled users are rejected on their existing session (401); an inactive project member, former lead, and revoked manager could no longer write (403). The task/event rows remained. An inactive team member retains project-level roster read access as noted above but has no team-scoped write privilege. Existing safe user response DTOs are used; collaboration responses contain no password hash, session ID, or database config.

## Migration review

`database/migrations/20260926-collaboration.sql` creates only `project_managers`, `project_teams`, `team_members`, `work_tasks`, and `work_task_events`; their definitions match the appended section of `database/schema.sql`. On isolated MySQL 8.0.46, an existing 24-table baseline became 29 tables. All five Administration tables remained. A sentinel user and Credit balance had the same SHA-256 before and after migration (`a3c6f162e43c2689368318260702463ccb38b79f96f89bb25ea0f15ecfc0652f`). The migration has no `CREATE DATABASE`, `USE`, `IF NOT EXISTS`, old-table rename/drop, or Credit update. It is a one-time DDL migration with MySQL implicit commits; do not blindly rerun after a partial failure. The runtime DML user requires no new DDL privilege.

## Browser and HTTP acceptance

| Scenario | Result |
| --- | --- |
| ADMIN appoints manager A; manager A does not gain platform user administration | PASS: browser appointment and HTTP 403 for platform users |
| Manager A creates groups, adds project members, appoints lead, creates and assigns task | PASS in isolated browser |
| Lead A sees own group and cannot write group B or project B | PASS: browser controls and direct HTTP 403 |
| Member A starts/submits assigned work and cannot manage groups or accept own work | PASS: browser plus direct HTTP 403 |
| Independent reviewer returns, then accepts; actor/time/event history persists | PASS in isolated browser |
| Manager assigned to own task cannot self-accept | PASS: direct HTTP 403 |
| Cross-project access, disabled/inactive user, old lead and revoked manager | PASS: direct HTTP 403/401 and retained history |
| Group member set inactive | PASS: write removal; project-wide roster read remains 200 by current policy |
| English and zh-CN labels, open details, statuses, event history, local time | PASS in real browser on final WAR |

The browser fixture used seven temporary accounts in two projects on the isolated `127.0.0.1:13307` MySQL instance. No daily development database or production server was contacted.

## Automated tests and final WAR

- `mvn test`: **121/121 PASS**.
- `mvn -Pmysql-tests test`: **322/322 PASS**.
- `mvn -Pmysql-tests clean package`: **322/322 PASS**, WAR build successful.
- `node --check src/main/webapp/assets/js/collaboration.js`: PASS.
- Final artifact: `target/veriqra-0.1.0-SNAPSHOT.war`.
- SHA-256: `A257A0AEE84A5B6112E4A3C050C594DBE5A6485F1D0A66AE37027ED24963195E`.
- ROOT WAR: `/`, login, collaboration JS and both i18n files returned 200; unauthenticated `/api/auth/me` returned 401; manager browser workflow passed.
- Named WAR: `/veriqra` returned the same baseline statuses, login returned 200, Cookie Path was `/veriqra`, and authenticated team/task APIs returned 200.
- WAR contains the collaboration classes/assets and Connector/J, without local database properties.
- `git diff --check`: PASS.

The temporary browser fixture was removed (test schema: 0 tables), the migration verification schema was dropped, both Tomcat hosts were stopped, and temporary MySQL shut down cleanly; ports 13307 and the test HTTP ports had no listener. The temporary MySQL process had stopped unexpectedly before the named-context smoke; it was restarted against the same isolated data directory, then explicitly shut down after that smoke passed. The external validation logs and offline MySQL data directory remain outside the repository because automatic approval review blocked recursive directory deletion. Temporary harness source/classes, scripts, and test credentials were removed individually. No production/development data, Credit behavior, AI feature, or other planned UI polish was changed. No commit or push was made.

## Release readiness

- **FREEZE COLLABORATION V1 SOURCE: YES.** No Critical/High blocker remains.
- **COMMIT: READY. PUSH: READY.** Review the uncommitted implementation and this report together first.
- **PRODUCTION MIGRATION: NOT YET READY TO EXECUTE.** It requires a fresh production backup, live 24-table/preflight check, DDL administrator, reviewed change window and explicit production authorization.
- **PRODUCTION DEPLOYMENT: NOT YET READY TO EXECUTE.** Deploy only after the migration and release smoke plan are approved; this review made no production change.

Remaining nonblocking scope limits: no task pagination, project-wide roster visibility, no separate manager/team change audit stream, and no Claim workflow. These are documented current behaviors, not features silently assumed to exist.
