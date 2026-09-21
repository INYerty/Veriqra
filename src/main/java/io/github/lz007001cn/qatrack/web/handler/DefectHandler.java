package io.github.lz007001cn.qatrack.web.handler;
import io.github.lz007001cn.qatrack.model.DefectStatus;
import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.web.*;
import io.github.lz007001cn.qatrack.web.dto.*;
import jakarta.servlet.http.*;
import java.io.IOException;

public final class DefectHandler {
    private DefectHandler() { }
    public static void handle(HttpServletRequest req, HttpServletResponse res, WebServices s,
                              Long actor, Long project, String[] path) throws IOException {
        String method = req.getMethod();
        if (path.length == 4) {
            JsonHttp.method(res, method, "GET", "POST");
            if (method.equals("GET")) {
                JsonHttp.write(res, 200, s.defects().listByProject(actor, project).stream().map(DefectResponse::from).toList());
            } else {
                var b = JsonHttp.read(req, CreateDefectRequest.class);
                var value = s.defects().create(actor, new CreateDefectCommand(project, b.failureAttemptId(), b.title(),
                        b.description(), b.severity(), b.priority(), b.assigneeId()));
                res.setHeader("Location", req.getContextPath() + "/api/projects/" + project + "/defects/" + value.id());
                JsonHttp.write(res, 201, DefectResponse.from(value));
            }
            return;
        }
        if (path.length == 5) {
            Long id = JsonHttp.positiveId(path[4]);
            JsonHttp.method(res, method, "GET", "PUT");
            if (method.equals("GET")) {
                JsonHttp.write(res, 200, DefectDetailResponse.from(s.defects().getDetails(actor, project, id)));
            } else {
                var b = JsonHttp.read(req, UpdateDefectRequest.class);
                JsonHttp.write(res, 200, DefectResponse.from(s.defects().update(actor, project,
                        new UpdateDefectCommand(id, b.title(), b.description(), b.severity(), b.priority(), b.assigneeId(), b.expectedVersion()))));
            }
            return;
        }
        if (path.length == 6) {
            Long id = JsonHttp.positiveId(path[4]);
            switch (path[5]) {
                case "start", "close" -> {
                    JsonHttp.method(res, method, "POST");
                    var b = JsonHttp.read(req, ExpectedVersionRequest.class);
                    s.defects().transition(actor, project, new TransitionDefectCommand(id,
                            path[5].equals("start") ? DefectStatus.IN_PROGRESS : DefectStatus.CLOSED, null, b.expectedVersion()));
                }
                case "resolve" -> {
                    JsonHttp.method(res, method, "POST");
                    var b = JsonHttp.read(req, ResolveDefectRequest.class);
                    s.defects().transition(actor, project, new TransitionDefectCommand(id, DefectStatus.RESOLVED, b.resolutionNote(), b.expectedVersion()));
                }
                case "reopen" -> {
                    JsonHttp.method(res, method, "POST");
                    var b = JsonHttp.read(req, ReopenDefectRequest.class);
                    s.defects().reopen(actor, project, new ReopenDefectCommand(id, b.failureAttemptId(), b.assigneeId(), b.expectedVersion()));
                }
                case "evidence" -> {
                    JsonHttp.method(res, method, "POST");
                    var b = JsonHttp.read(req, AddEvidenceRequest.class);
                    s.defects().addEvidence(actor, project, id, b.failureAttemptId());
                }
                default -> throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
            }
            res.setStatus(204);
            return;
        }
        // Explicit correction action follows the existing relation-removal API convention.
        if (path.length == 8 && path[5].equals("evidence") && path[7].equals("remove")) {
            Long id = JsonHttp.positiveId(path[4]), attempt = JsonHttp.positiveId(path[6]);
            JsonHttp.method(res, method, "POST");
            JsonHttp.read(req, EmptyActionRequest.class);
            s.defects().removeEvidence(actor, project, id, attempt);
            res.setStatus(204);
            return;
        }
        throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
    }
}
