# Release and runtime information

Administration → System is an ADMIN-only, read-only view. The `/api/admin/system` response includes an allowlist of application version, build commit, UTC build time, application/database status, database version and table count, server time, application display time zone, and the existing non-secret runtime fields. It never returns database credentials, a JDBC URL, environment variables, filesystem paths, or full system properties.

Maven filters `build-info.properties` into `target/classes` and the WAR. The version comes from `project.version`; the full Git commit and UTC build time come from the Git commit ID Maven plugin at build time. Nothing generated is written to tracked source. When Git metadata is unavailable, the commit is `unknown`; unresolved or invalid metadata is also displayed as `unknown` rather than leaking a template expression. A release build must verify the displayed commit equals `git rev-parse HEAD` and recompute the WAR SHA-256 after packaging.

Database status `UP` means the ADMIN-authorized System read successfully queried the database. If the database is unavailable, authentication/authorization may itself fail, so this page cannot act as an independent outage monitor; it does not claim `DOWN` from cached data. Table count is an observable schema size, **not** a migration version. The UI formats UTC build/server instants for the existing Asia/Shanghai display time zone in both supported locales.

The same API URL is relative to the WAR context, so ROOT and `/veriqra` deployments use identical behavior. Ordinary users receive the existing 403 response.
