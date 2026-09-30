# Project creation and collaboration workflow

Date: 2026-09-30

Baseline: `eebbf5707c5f978be9ec3251c295c68f13e770f8`.
These are local, uncommitted UI changes. The deployed R5.2-C baseline is unchanged.

## Scope and findings

The backend already provided `POST /api/projects`, but the workspace had no project
creation form. Collaboration rendered unrelated panels in a long page, with manager
appointment before project membership. An ADMIN viewing a project without membership
could also receive a global error from member-only contribution or handoff queries.

The change adds the missing project creation entry and reorganizes the existing forms.
It does not change Java, schema, DAO, Service, authentication, or permission rules.

## Where to click

1. Sign in as a platform ADMIN. Use **Create project** beside **Current project**.
   Enter a unique key (1–16 uppercase letters/digits starting with a letter), a name,
   and an optional description. The created project is selected automatically.
2. Choose **Set up members and managers** in the success message, or open
   **Teams & tasks → Members & managers**.
3. Add an existing platform username as a project member, selecting Tester or Developer.
   Add the intended project manager too; this may be the ADMIN's own existing account.
4. In the next panel, select that active member and choose **Appoint manager**.
   Having an ADMIN account alone does not grant project manager duties.
5. As an appointed project manager, open **Teams**. Enter a team name, choose an active
   project member as lead, and choose **Create team**. The lead joins automatically.
6. Select the team to see its members. Select additional project members and choose
   **Add to team**. Leads and project managers manage team membership under existing rules.
7. Open **Tasks**, select the team and an active team member, enter a title and optional
   description/reward, then choose **Create task**. Managers or authorized team leads assign work.
8. Use **Credits & contribution** for personal balance/history, transfers, monthly
   contribution, and handoff offers. These panels no longer precede the initial setup flow.

The initial setup guide links to the matching areas and stays visible with five completion
indicators. It checks active project members, active manager appointments, active teams,
an active team with another eligible member besides its lead, and a visible assigned task.
Only the first incomplete step says **Next step**; later steps say to complete the earlier
setup first. Team membership is read from the existing API; while it loads, the last steps
say **Checking team membership**. These are onboarding hints, not authorization grants or
a claim that every team is staffed or that any task has been completed. Task filters do not
turn a previously observed assigned task back into an unfinished setup step.

## Permission and UI behavior

- Platform account administration stays separate from project responsibilities.
- Ordinary USER accounts do not see the Create project or Appoint manager controls.
- Project setup is exposed only after the created project is loaded and selected;
  a failed list reload cannot leave a setup link pointing at the previous project.
- Active membership alone does not grant project manager duties.
- Identity and prerequisite explanations accompany controls that are unavailable.
- Without active project membership, contribution/handoff queries are not sent.
  Their panels explain the prerequisite; expected 403 responses stay local to the panel.
  Personal Credit balance remains readable through the existing account endpoint.
- Project changes clear prior contribution, offer, and Credit display content.
- Operation feedback belongs to its originating area even if another area is selected.
  Project generations guard write completion; request and selection tokens guard reads,
  including late team/task errors. Core read loading is marked on the matching region.
- Archive state is explained and reflected in appointment, transfer, and add-member
  controls. Existing server checks remain authoritative for every write operation.
- The new navigation uses native keyboard-operable buttons with `aria-pressed` and
  labeled regions. CSS wraps the buttons at narrow widths without changing sidebar behavior.

## Previous mock-stage validation (historical evidence)

- Node: **27/27 PASS**, including ten additional tests for project creation and
  collaboration guidance/permission/state behavior. Existing pagination and stale-response
  tests continue to pass. Both changed production JS files pass `node --check`.
- Maven `test`: **123/123 PASS**; the fast profile does not use MySQL.
- Maven `clean package`: **123/123 PASS**, **BUILD SUCCESS**.
- Real browser UI interaction with a disposable in-memory API fixture: ROOT and
  `/veriqra` paths load login, resources, project creation, and collaboration correctly.
  The ROOT flow exercised member addition, manager appointment, team creation,
  team member addition, task assignment, and the resulting task detail.
- English and zh-CN labels and duplicate-key errors were checked. Ordinary USER controls
  and a nonmember ADMIN's local Credit/contribution/handoff explanations were checked.
- 375px viewport: task controls did not cause horizontal document overflow; the
  hamburger opened the sidebar. Desktop layout and section switching were checked.
- Browser fixture results verify frontend behavior, not a new end-to-end JDBC or
  Tomcat deployment acceptance. No MySQL suite, development DB, or production access
  was used in this task. A real isolated Tomcat/MySQL smoke remains a release check.
- `git diff --check`: PASS. Disposable browser fixture stopped after verification.

The WAR is a local development build from an uncommitted working tree. Its embedded
Build Commit still describes the committed baseline, so it must not be presented as
a committed release. Review, commit, and a release build precede any production update.

Previous mock-stage artifact: `target/veriqra-0.1.0-SNAPSHOT.war`, **5,517,586 bytes**.
SHA-256: `bc6641cd6597cb768b92916a1ffb3c78066ae44c93016a7b0ae2f85eed969fe8`.
All six changed HTML/JS/CSS/locale resources match their final source bytes in the WAR;
no `.local.properties` file is packaged. Rebuilding requires a new hash.

## Real backend completion

The subsequent isolated MySQL/Tomcat acceptance, final scoped review, updated test results,
artifact hash, and cleanup evidence are recorded in
[R5.3-A final review](R5.3-A-COLLABORATION-WORKFLOW-UX-FINAL-REVIEW.md).
That report supersedes the mock stage's release-check limitation and artifact identity.
