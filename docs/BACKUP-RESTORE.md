# Veriqra logical backup and restore drill

The presence of a `.sql` file does not establish recoverability. A release backup is accepted only after its hash is verified, it is restored into an isolated MySQL instance, table/data sanity checks pass, and a compatible WAR starts against the restored database. Never restore onto the production `veriqra` schema for a drill.

## Safe procedure

1. Record the release commit, running WAR SHA-256, MySQL version, table count, and a new backup directory. Preserve earlier backups.
2. Check every table's storage engine before relying on `mysqldump --single-transaction`; report any nontransactional table. Dump with `--single-transaction --routines --triggers --set-gtid-purged=OFF` using an administrator-controlled account. Do not stop the live application or MySQL and do not put passwords on a command line.
3. Record backup size and SHA-256 on the server. Copy the dump into a private directory **outside the repository** and compare its SHA-256 again. Treat the copy as production-sensitive data.
4. Start a dedicated loopback-only MySQL 8.0.46 instance with an isolated data directory. Create a new restore schema; import the dump. Never run cleanup or restore commands against `localhost:3306/veriqra`.
5. Count tables, check required table names, and compare non-sensitive `COUNT(*)` / `MAX(id)` values for key tables. Do not print password hashes, complete user rows, tokens, or business descriptions.
6. Point the matching, hash-verified WAR at the restored schema through a temporary external DB config. Check startup, login page, unauthenticated auth response, workspace and administration assets. Run deeper reads only with explicitly provisioned isolated test identities. Stop Tomcat afterward.
7. Drop the isolated restore schemas, stop the test MySQL, verify no listener remains, and remove local dump copies and temporary credentials. Keep production backup files in their managed location; retention is a separate operational decision.

## R5.2-C drill evidence (2026-09-29)

| Snapshot | SHA-256 | Isolated tables | Safe restored counts (users / projects / requirements / test cases / credit accounts / credit transactions) | Matching WAR startup |
| --- | --- | ---: | --- | --- |
| Predeploy R5.2-B | `30744fbddb4af8f5117e66728bb8b181b819372a23ee6a266bf5e3d9c1bb7e77` | 24 | 4 / 1 / 1 / 1 / 4 / 2 | Passed with backed-up old WAR |
| Current postdeploy R5.2-B | `72d433afbf2a787137b351232272798e3906b4f1e5873c107f72d8dd1178e2f3` | 32 | 4 / 1 / 1 / 1 / 4 / 2 | Passed with exact running WAR |

The old snapshot has no Collaboration or R5.2-B task-credit tables, as expected. The current snapshot contains all eight. Current snapshot counts for credit transfers, handoff offers, and contribution events were each zero and matched the live database at the time of the dump. Both restored schemas were created outside the development and production databases. See the R5.2-C final report for environment cleanup and final validation status.

An application rollback using the old WAR and 24-table backup would discard postdeploy writes if the database itself were restored. The exercise confirms startup compatibility of the saved pair; it does not authorize or automate a production rollback. Any real rollback needs a separate change plan and a fresh assessment of data created after the backup.
