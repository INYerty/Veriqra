package io.github.lz007001cn.veriqra.web.handler;

import io.github.lz007001cn.veriqra.model.*;
import io.github.lz007001cn.veriqra.service.CollaborationService;
import io.github.lz007001cn.veriqra.web.*;
import jakarta.servlet.http.*;
import java.io.IOException;

/** Small project-scoped route surface; all authorization is repeated in the Service. */
public final class CollaborationHandler {
    private CollaborationHandler() { }
    public record ManagerRequest(Long userId) { }
    public record MemberRequest(String username, ProjectRole projectRole) { }
    public record TeamRequest(String name, Long leadUserId) { }
    public record LeadRequest(Long leadUserId, Integer expectedVersion) { }
    public record TeamMemberRequest(Long userId, MembershipStatus status) { }
    public record TaskRequest(Long teamId, String title, String description, Long assigneeUserId, String rewardCredit) { }
    public record ReassignRequest(Long assigneeUserId, Integer expectedVersion) { }
    public record TransitionRequest(WorkTaskStatus status, String note, Integer expectedVersion) { }
    public record RewardRequest(String rewardCredit, Integer expectedVersion) { }
    public record TransferRequest(Long recipientUserId, String amount, String note, String operationId) { }
    public record HandoffRequest(Long taskId, Long recipientUserId, String amount, String note, String operationId) { }

    public static void handle(HttpServletRequest req, HttpServletResponse res, CollaborationService service,
                              Long actor, Long project, String[] path) throws IOException {
        String method = req.getMethod();
        if (path.length == 4) {
            switch (path[3]) {
                case "managers" -> {
                    JsonHttp.method(res, method, "GET", "POST");
                    if (method.equals("GET")) JsonHttp.write(res, 200, service.listManagers(actor, project));
                    else JsonHttp.write(res, 201, service.appointManager(actor, project, JsonHttp.read(req, ManagerRequest.class).userId()));
                }
                case "members" -> {
                    JsonHttp.method(res, method, "GET", "POST");
                    if (method.equals("GET")) JsonHttp.write(res, 200, service.listProjectMembers(actor, project));
                    else {
                        MemberRequest body = JsonHttp.read(req, MemberRequest.class);
                        JsonHttp.write(res, 201, service.addProjectMember(actor, project, body.username(), body.projectRole()));
                    }
                }
                case "teams" -> {
                    JsonHttp.method(res, method, "GET", "POST");
                    if (method.equals("GET")) JsonHttp.write(res, 200, service.listTeams(actor, project));
                    else {
                        TeamRequest body = JsonHttp.read(req, TeamRequest.class);
                        var team = service.createTeam(actor, project, body.name(), body.leadUserId());
                        res.setHeader("Location", req.getContextPath() + "/api/projects/" + project + "/teams/" + team.id());
                        JsonHttp.write(res, 201, team);
                    }
                }
                case "tasks" -> {
                    JsonHttp.method(res, method, "GET", "POST");
                    if (method.equals("GET")) JsonHttp.write(res, 200, service.listTasks(actor, project));
                    else {
                        TaskRequest body = JsonHttp.read(req, TaskRequest.class);
                        var task = service.createTask(actor, project, body.teamId(), body.title(), body.description(), body.assigneeUserId(),
                                body.rewardCredit() == null ? 0L : credit(body.rewardCredit()));
                        res.setHeader("Location", req.getContextPath() + "/api/projects/" + project + "/tasks/" + task.id());
                        JsonHttp.write(res, 201, task);
                    }
                }
                case "credit-transfers" -> {
                    JsonHttp.method(res, method, "POST");
                    TransferRequest body = JsonHttp.read(req, TransferRequest.class);
                    JsonHttp.write(res, 201, service.transferCredit(actor, project, body.recipientUserId(),
                            credit(body.amount()), body.note(), body.operationId()));
                }
                case "contributions" -> {
                    JsonHttp.method(res, method, "GET");
                    JsonHttp.write(res, 200, service.monthlyContribution(actor, project, req.getParameter("month")));
                }
                case "handoffs" -> {
                    JsonHttp.method(res, method, "GET", "POST");
                    if (method.equals("GET")) JsonHttp.write(res, 200, service.listHandoffs(actor, project));
                    else {
                        HandoffRequest body = JsonHttp.read(req, HandoffRequest.class);
                        JsonHttp.write(res, 201, service.offerHandoff(actor, project, body.taskId(), body.recipientUserId(),
                                credit(body.amount()), body.note(), body.operationId()));
                    }
                }
                default -> throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
            }
            return;
        }
        if (path.length == 5 && path[3].equals("managers")) {
            JsonHttp.method(res, method, "DELETE");
            service.revokeManager(actor, project, JsonHttp.positiveId(path[4]));
            res.setStatus(204);
            return;
        }
        if (path.length == 5 && path[3].equals("tasks")) {
            JsonHttp.method(res, method, "GET");
            JsonHttp.write(res, 200, service.getTask(actor, project, JsonHttp.positiveId(path[4])));
            return;
        }
        if (path.length == 6 && path[3].equals("teams")) {
            Long team = JsonHttp.positiveId(path[4]);
            if (path[5].equals("members")) {
                JsonHttp.method(res, method, "GET", "POST");
                if (method.equals("GET")) JsonHttp.write(res, 200, service.listTeamMembers(actor, project, team));
                else {
                    TeamMemberRequest body = JsonHttp.read(req, TeamMemberRequest.class);
                    JsonHttp.write(res, 200, service.setTeamMember(actor, project, team, body.userId(), body.status()));
                }
                return;
            }
            if (path[5].equals("lead")) {
                JsonHttp.method(res, method, "POST");
                LeadRequest body = JsonHttp.read(req, LeadRequest.class);
                JsonHttp.write(res, 200, service.changeTeamLead(actor, project, team, body.leadUserId(), body.expectedVersion()));
                return;
            }
        }
        if (path.length == 6 && path[3].equals("tasks")) {
            Long task = JsonHttp.positiveId(path[4]);
            JsonHttp.method(res, method, "POST");
            if (path[5].equals("assignee")) {
                ReassignRequest body = JsonHttp.read(req, ReassignRequest.class);
                JsonHttp.write(res, 200, service.reassignTask(actor, project, task, body.assigneeUserId(), body.expectedVersion()));
                return;
            }
            if (path[5].equals("status")) {
                TransitionRequest body = JsonHttp.read(req, TransitionRequest.class);
                JsonHttp.write(res, 200, service.transitionTask(actor, project, task, body.status(), body.note(), body.expectedVersion()));
                return;
            }
            if (path[5].equals("reward")) {
                RewardRequest body = JsonHttp.read(req, RewardRequest.class);
                JsonHttp.write(res, 200, service.setTaskReward(actor, project, task,
                        credit(body.rewardCredit()), body.expectedVersion()));
                return;
            }
        }
        if (path.length == 6 && path[3].equals("handoffs")) {
            Long offer = JsonHttp.positiveId(path[4]);
            JsonHttp.method(res, method, "POST");
            switch (path[5]) {
                case "accept" -> JsonHttp.write(res, 200, service.acceptHandoff(actor, project, offer));
                case "decline" -> JsonHttp.write(res, 200, service.declineHandoff(actor, project, offer));
                case "cancel" -> JsonHttp.write(res, 200, service.cancelHandoff(actor, project, offer));
                default -> throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
            }
            return;
        }
        throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
    }
    private static long credit(String value) {
        try {
            if (value == null || !value.matches("(?:0|[1-9][0-9]*)")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException e) { throw new HttpFailure(400, "INVALID_CREDIT_AMOUNT", "Credit amount must be an integer within BIGINT range"); }
    }
}
