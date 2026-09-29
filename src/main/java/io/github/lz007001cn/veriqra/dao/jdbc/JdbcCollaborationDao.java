package io.github.lz007001cn.veriqra.dao.jdbc;

import io.github.lz007001cn.veriqra.dao.CollaborationDao;
import io.github.lz007001cn.veriqra.admin.Page;
import io.github.lz007001cn.veriqra.exception.DataAccessException;
import io.github.lz007001cn.veriqra.exception.OptimisticLockException;
import io.github.lz007001cn.veriqra.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Non-owning JDBC DAO. All writes use the caller's transaction and never close its Connection. */
public final class JdbcCollaborationDao implements CollaborationDao {
    private static final String MANAGER = "SELECT project_id,user_id,appointed_by,status,appointed_at,updated_at FROM project_managers";
    private static final String TEAM = "SELECT id,project_id,name,lead_user_id,status,created_by,created_at,updated_at,lock_version FROM project_teams";
    private static final String MEMBER = "SELECT team_id,project_id,user_id,status,joined_at,updated_at FROM team_members";
    private static final String TASK = "SELECT id,project_id,team_id,title,description,reward_credit,assignee_user_id,created_by,status,accepted_by,accepted_at,created_at,updated_at,lock_version FROM work_tasks";
    private static final String EVENT = "SELECT id,task_id,actor_user_id,event_type,from_status,to_status,from_assignee_user_id,to_assignee_user_id,note,created_at FROM work_task_events";
    private final Connection connection;

    public JdbcCollaborationDao(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    @FunctionalInterface private interface Bind { void accept(PreparedStatement s) throws SQLException; }
    @FunctionalInterface private interface MapRow<T> { T apply(ResultSet rs) throws SQLException; }

    private <T> List<T> query(String operation, String sql, Bind bind, MapRow<T> mapper) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            bind.accept(s);
            try (ResultSet rs = s.executeQuery()) {
                List<T> result = new ArrayList<>();
                while (rs.next()) {
                    try { result.add(mapper.apply(rs)); }
                    catch (IllegalArgumentException e) { throw new SQLException("Unsupported collaboration enum", "22000", e); }
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) { throw new DataAccessException(operation, e); }
    }
    private <T> Optional<T> one(String operation, String sql, Bind bind, MapRow<T> mapper) {
        return query(operation, sql, bind, mapper).stream().findFirst();
    }
    private void write(String operation, String sql, Bind bind) {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            bind.accept(s);
            // MySQL upsert reports 1 for insert, 2 for update, or 0 for an unchanged row.
            s.executeUpdate();
        } catch (SQLException e) { throw new DataAccessException(operation, e); }
    }
    private long insert(String operation, String sql, Bind bind) {
        try (PreparedStatement s = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind.accept(s);
            if (s.executeUpdate() != 1) throw new SQLException("Unexpected insert count");
            try (ResultSet keys = s.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("Generated key unavailable");
                return keys.getLong(1);
            }
        } catch (SQLException e) { throw new DataAccessException(operation, e); }
    }
    private void requireTransaction() {
        try { if (connection.getAutoCommit()) throw new SQLException("Locking requires an outer transaction", "25000"); }
        catch (SQLException e) { throw new DataAccessException("Lock collaboration row", e); }
    }
    private static void nullableLong(PreparedStatement s, int position, Long value) throws SQLException {
        s.setObject(position, value, Types.BIGINT);
    }
    private static void nullableTime(PreparedStatement s, int position, LocalDateTime value) throws SQLException {
        s.setObject(position, value, Types.TIMESTAMP);
    }

    @Override public Optional<ProjectManager> findManager(long projectId, long userId) {
        return one("Find ProjectManager", MANAGER + " WHERE project_id=? AND user_id=?",
                s -> { s.setLong(1, projectId); s.setLong(2, userId); }, JdbcCollaborationDao::manager);
    }
    @Override public List<ProjectManager> listManagers(long projectId) {
        return query("List ProjectManager", MANAGER + " WHERE project_id=? ORDER BY user_id",
                s -> s.setLong(1, projectId), JdbcCollaborationDao::manager);
    }
    @Override public ProjectManager appointManager(long projectId, long userId, long actorId) {
        write("Appoint ProjectManager", "INSERT INTO project_managers(project_id,user_id,appointed_by,status) VALUES(?,?,?,'ACTIVE') "
                        + "ON DUPLICATE KEY UPDATE appointed_by=?,status='ACTIVE',appointed_at=CURRENT_TIMESTAMP(6),updated_at=CURRENT_TIMESTAMP(6)",
                s -> { s.setLong(1, projectId); s.setLong(2, userId); s.setLong(3, actorId); s.setLong(4, actorId); });
        return findManager(projectId, userId).orElseThrow(() -> new DataAccessException("Appointed manager could not be read"));
    }
    @Override public void revokeManager(long projectId, long userId) {
        write("Revoke ProjectManager", "UPDATE project_managers SET status='INACTIVE',updated_at=CURRENT_TIMESTAMP(6) WHERE project_id=? AND user_id=? AND status='ACTIVE'",
                s -> { s.setLong(1, projectId); s.setLong(2, userId); });
    }

    @Override public ProjectTeam insertTeam(long projectId, String name, long leadId, long actorId) {
        long id = insert("Insert ProjectTeam", "INSERT INTO project_teams(project_id,name,lead_user_id,created_by) VALUES(?,?,?,?)",
                s -> { s.setLong(1, projectId); s.setString(2, name); s.setLong(3, leadId); s.setLong(4, actorId); });
        return findTeam(id).orElseThrow(() -> new DataAccessException("Inserted team could not be read"));
    }
    @Override public Optional<ProjectTeam> findTeam(long teamId) {
        return one("Find ProjectTeam", TEAM + " WHERE id=?", s -> s.setLong(1, teamId), JdbcCollaborationDao::team);
    }
    @Override public Optional<ProjectTeam> findTeamForUpdate(long teamId) {
        requireTransaction();
        return one("Lock ProjectTeam", TEAM + " WHERE id=? FOR UPDATE", s -> s.setLong(1, teamId), JdbcCollaborationDao::team);
    }
    @Override public List<ProjectTeam> listTeams(long projectId) {
        return query("List ProjectTeam", TEAM + " WHERE project_id=? ORDER BY id", s -> s.setLong(1, projectId), JdbcCollaborationDao::team);
    }
    @Override public ProjectTeam updateTeam(ProjectTeam value) {
        JdbcValues.writableVersion(value.lockVersion());
        try (PreparedStatement s = connection.prepareStatement("UPDATE project_teams SET lead_user_id=?,status=?,updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1 WHERE id=? AND lock_version=?")) {
            s.setLong(1, value.leadUserId()); s.setString(2, value.status().name());
            s.setLong(3, value.id()); s.setInt(4, value.lockVersion());
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findTeam(value.id()).orElseThrow(() -> new DataAccessException("Updated team could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update ProjectTeam", e); }
    }
    @Override public Optional<TeamMember> findTeamMember(long teamId, long userId) {
        return one("Find TeamMember", MEMBER + " WHERE team_id=? AND user_id=?",
                s -> { s.setLong(1, teamId); s.setLong(2, userId); }, JdbcCollaborationDao::teamMember);
    }
    @Override public List<TeamMember> listTeamMembers(long teamId) {
        return query("List TeamMember", MEMBER + " WHERE team_id=? ORDER BY user_id", s -> s.setLong(1, teamId), JdbcCollaborationDao::teamMember);
    }
    @Override public TeamMember setTeamMember(long teamId, long projectId, long userId, MembershipStatus status) {
        write("Set TeamMember", "INSERT INTO team_members(team_id,project_id,user_id,status) VALUES(?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE project_id=?,status=?,updated_at=CURRENT_TIMESTAMP(6)",
                s -> { s.setLong(1, teamId); s.setLong(2, projectId); s.setLong(3, userId);
                    s.setString(4, status.name()); s.setLong(5, projectId); s.setString(6, status.name()); });
        return findTeamMember(teamId, userId).orElseThrow(() -> new DataAccessException("Team member could not be read"));
    }

    @Override public WorkTask insertTask(long projectId, long teamId, String title, String description, long assigneeId, long actorId) {
        return insertTask(projectId, teamId, title, description, 0, assigneeId, actorId);
    }
    @Override public WorkTask insertTask(long projectId, long teamId, String title, String description, long rewardCredit, long assigneeId, long actorId) {
        long id = insert("Insert WorkTask", "INSERT INTO work_tasks(project_id,team_id,title,description,reward_credit,assignee_user_id,created_by) VALUES(?,?,?,?,?,?,?)",
                s -> { s.setLong(1, projectId); s.setLong(2, teamId); s.setString(3, title);
                    s.setString(4, description); s.setLong(5, rewardCredit); s.setLong(6, assigneeId); s.setLong(7, actorId); });
        return findTask(id).orElseThrow(() -> new DataAccessException("Inserted task could not be read"));
    }
    @Override public Optional<WorkTask> findTask(long taskId) {
        return one("Find WorkTask", TASK + " WHERE id=?", s -> s.setLong(1, taskId), JdbcCollaborationDao::task);
    }
    @Override public Optional<WorkTask> findTaskForUpdate(long taskId) {
        requireTransaction();
        return one("Lock WorkTask", TASK + " WHERE id=? FOR UPDATE", s -> s.setLong(1, taskId), JdbcCollaborationDao::task);
    }
    @Override public List<WorkTask> listTasks(long projectId) {
        return query("List WorkTask", TASK + " WHERE project_id=? ORDER BY id DESC", s -> s.setLong(1, projectId), JdbcCollaborationDao::task);
    }
    @Override public Page<WorkTask> pageTasks(long projectId, long actorId, boolean manager, WorkTaskStatus status,
                                               Long teamId, Long assigneeId, int page, int pageSize) {
        // Apply visibility and filters before both COUNT and LIMIT. The lead predicate mirrors isActiveLead.
        StringBuilder where = new StringBuilder(" WHERE t.project_id=? AND (?=1 OR t.assignee_user_id=? OR EXISTS ("
                + "SELECT 1 FROM project_teams pt JOIN team_members tm ON tm.team_id=pt.id AND tm.user_id=? AND tm.status='ACTIVE' "
                + "JOIN project_members pm ON pm.project_id=pt.project_id AND pm.user_id=? AND pm.status='ACTIVE' "
                + "WHERE pt.id=t.team_id AND pt.project_id=t.project_id AND pt.status='ACTIVE' AND pt.lead_user_id=?))");
        if (status != null) where.append(" AND t.status=?");
        if (teamId != null) where.append(" AND t.team_id=?");
        if (assigneeId != null) where.append(" AND t.assignee_user_id=?");
        try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM work_tasks t" + where);
             PreparedStatement rows = connection.prepareStatement(TASK + " t" + where + " ORDER BY t.id DESC LIMIT ? OFFSET ?")) {
            bindTaskScope(count, projectId, actorId, manager, status, teamId, assigneeId);
            int next = bindTaskScope(rows, projectId, actorId, manager, status, teamId, assigneeId);
            rows.setInt(next++, pageSize);
            rows.setLong(next, ((long) page - 1) * pageSize);
            long total;
            try (ResultSet result = count.executeQuery()) { result.next(); total = result.getLong(1); }
            List<WorkTask> items = new ArrayList<>();
            try (ResultSet result = rows.executeQuery()) {
                while (result.next()) items.add(task(result));
            }
            return new Page<>(items, total, page, pageSize);
        } catch (SQLException e) { throw new DataAccessException("Page visible WorkTask", e); }
    }
    private static int bindTaskScope(PreparedStatement s, long projectId, long actorId, boolean manager,
                                      WorkTaskStatus status, Long teamId, Long assigneeId) throws SQLException {
        int i = 1;
        s.setLong(i++, projectId);
        s.setInt(i++, manager ? 1 : 0);
        s.setLong(i++, actorId); // assignee
        s.setLong(i++, actorId); // active team membership
        s.setLong(i++, actorId); // active project membership
        s.setLong(i++, actorId); // current lead
        if (status != null) s.setString(i++, status.name());
        if (teamId != null) s.setLong(i++, teamId);
        if (assigneeId != null) s.setLong(i++, assigneeId);
        return i;
    }
    @Override public WorkTask updateTask(WorkTask value) {
        JdbcValues.writableVersion(value.lockVersion());
        try (PreparedStatement s = connection.prepareStatement("UPDATE work_tasks SET assignee_user_id=?,status=?,accepted_by=?,accepted_at=?,updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1 WHERE id=? AND lock_version=?")) {
            s.setLong(1, value.assigneeUserId()); s.setString(2, value.status().name());
            nullableLong(s, 3, value.acceptedBy()); nullableTime(s, 4, value.acceptedAt());
            s.setLong(5, value.id()); s.setInt(6, value.lockVersion());
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findTask(value.id()).orElseThrow(() -> new DataAccessException("Updated task could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update WorkTask", e); }
    }
    @Override public WorkTask updateTaskReward(long taskId, long rewardCredit, int expectedVersion) {
        try (PreparedStatement s = connection.prepareStatement("UPDATE work_tasks SET reward_credit=?,updated_at=CURRENT_TIMESTAMP(6),lock_version=lock_version+1 WHERE id=? AND lock_version=? AND status='OPEN'")) {
            s.setLong(1, rewardCredit); s.setLong(2, taskId); s.setInt(3, expectedVersion);
            if (s.executeUpdate() != 1) throw new OptimisticLockException();
            return findTask(taskId).orElseThrow(() -> new DataAccessException("Updated task could not be read"));
        } catch (SQLException e) { throw new DataAccessException("Update WorkTask reward", e); }
    }
    @Override public WorkTaskEvent appendEvent(WorkTaskEvent value) {
        long id = insert("Append WorkTaskEvent", "INSERT INTO work_task_events(task_id,actor_user_id,event_type,from_status,to_status,from_assignee_user_id,to_assignee_user_id,note) VALUES(?,?,?,?,?,?,?,?)",
                s -> { s.setLong(1, value.taskId()); s.setLong(2, value.actorUserId()); s.setString(3, value.eventType().name());
                    s.setString(4, value.fromStatus() == null ? null : value.fromStatus().name());
                    s.setString(5, value.toStatus().name()); nullableLong(s, 6, value.fromAssigneeUserId());
                    s.setLong(7, value.toAssigneeUserId()); s.setString(8, value.note()); });
        return one("Find WorkTaskEvent", EVENT + " WHERE id=?", s -> s.setLong(1, id), JdbcCollaborationDao::event)
                .orElseThrow(() -> new DataAccessException("Appended event could not be read"));
    }
    @Override public List<WorkTaskEvent> listEvents(long taskId) {
        return query("List WorkTaskEvent", EVENT + " WHERE task_id=? ORDER BY id", s -> s.setLong(1, taskId), JdbcCollaborationDao::event);
    }

    private static ProjectManager manager(ResultSet r) throws SQLException {
        return new ProjectManager(r.getLong("project_id"), r.getLong("user_id"), r.getLong("appointed_by"),
                MembershipStatus.valueOf(r.getString("status")), r.getObject("appointed_at", LocalDateTime.class), r.getObject("updated_at", LocalDateTime.class));
    }
    private static ProjectTeam team(ResultSet r) throws SQLException {
        return new ProjectTeam(r.getLong("id"), r.getLong("project_id"), r.getString("name"), r.getLong("lead_user_id"),
                TeamStatus.valueOf(r.getString("status")), r.getLong("created_by"), r.getObject("created_at", LocalDateTime.class),
                r.getObject("updated_at", LocalDateTime.class), JdbcValues.version(r));
    }
    private static TeamMember teamMember(ResultSet r) throws SQLException {
        return new TeamMember(r.getLong("team_id"), r.getLong("project_id"), r.getLong("user_id"),
                MembershipStatus.valueOf(r.getString("status")), r.getObject("joined_at", LocalDateTime.class),
                r.getObject("updated_at", LocalDateTime.class));
    }
    private static WorkTask task(ResultSet r) throws SQLException {
        return new WorkTask(r.getLong("id"), r.getLong("project_id"), r.getLong("team_id"), r.getString("title"),
                r.getString("description"), r.getLong("reward_credit"), r.getLong("assignee_user_id"), r.getLong("created_by"),
                WorkTaskStatus.valueOf(r.getString("status")), r.getObject("accepted_by", Long.class),
                r.getObject("accepted_at", LocalDateTime.class), r.getObject("created_at", LocalDateTime.class),
                r.getObject("updated_at", LocalDateTime.class), JdbcValues.version(r));
    }
    private static WorkTaskEvent event(ResultSet r) throws SQLException {
        String from = r.getString("from_status");
        return new WorkTaskEvent(r.getLong("id"), r.getLong("task_id"), r.getLong("actor_user_id"),
                WorkTaskEventType.valueOf(r.getString("event_type")), from == null ? null : WorkTaskStatus.valueOf(from),
                WorkTaskStatus.valueOf(r.getString("to_status")), r.getObject("from_assignee_user_id", Long.class),
                r.getLong("to_assignee_user_id"), r.getString("note"), r.getObject("created_at", LocalDateTime.class));
    }
}
