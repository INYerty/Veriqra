# Production acceptance fixture (design only)

This describes a future low-risk smoke check after a major Veriqra release. **No acceptance project, account, task, or Credit was created in production during R5.2-C.** Provisioning needs a separate, explicit production change and an administrator's approval.

## Dedicated scope

- Reserve one clearly labeled project, such as `Veriqra Production Acceptance`, solely for deployment checks. Never add real customer requirements, runs, defects, or people to it.
- Reuse a small set of dedicated accounts: one platform administrator for account provisioning, one project manager, and two project members. Suggested names are `qa_accept_admin`, `qa_accept_manager`, `qa_accept_member_a`, and `qa_accept_member_b`; these are **names only**, not pre-created credentials.
- Keep passwords in the approved secret store, rotate them on ownership changes, and do not put them in Git, deployment scripts, screenshots, or this document. Do not grant the manager account platform administrator rights.
- Document the project ID and account IDs in the private operations inventory. Mark all fixture tasks and audit notes with a release-specific acceptance prefix and timestamp.

## Per-release checks

After checking the release commit, WAR hash, database backup, and health, a designated operator can verify project visibility, manager/team/member permissions, task assignment and transition, a small peer Credit transfer between the fixture members, a paid handoff between those members, task reward, and project-scoped monthly contribution. Use low, explicitly labeled test Credit amounts only. Verify the resulting ledger and audit trail without changing real users or projects.

The administrator account is for administration checks; routine task and Credit actions use the manager/member accounts. A member must not gain user-management or password-reset privileges. Run any destructive/irreversible check only when specifically included in a separately reviewed release plan.

## Isolation and cleanup

Do not transfer or grant test Credit to a real user, assign a fixture task to a real employee, or attach fixture data to a real requirement or defect. Reuse the fixture project and accounts across releases instead of creating new ones every time. After each check, retain the minimum auditable task/ledger record required by the current model, and remove or close only records for which the product supports safe cleanup. Record any residual test balance and reconcile it **within the fixture accounts** before the next release. Never hard-delete financial or task history just to make the UI look empty.

Contribution is currently project-scoped, so acceptance activity stays in the acceptance project. A future cross-project or global ranking must explicitly exclude this fixture; do not assume project isolation will automatically carry into a new leaderboard.

Access, audit, task-event, Credit-ledger, and contribution records should show the expected fixture actors and project. Record test outcome, release commit, and cleanup result in the deployment handoff without copying passwords or personal data.
