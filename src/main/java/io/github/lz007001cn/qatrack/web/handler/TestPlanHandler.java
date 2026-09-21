package io.github.lz007001cn.qatrack.web.handler;
import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.web.*;
import io.github.lz007001cn.qatrack.web.dto.*;
import jakarta.servlet.http.*;
import java.io.IOException;

public final class TestPlanHandler {
    private TestPlanHandler() { }
    public static void handle(HttpServletRequest req, HttpServletResponse res, WebServices s,
                              Long actor, Long project, String[] path) throws IOException {
        String method = req.getMethod();
        if (path.length == 4) {
            JsonHttp.method(res, method, "GET", "POST");
            if (method.equals("GET")) {
                JsonHttp.write(res, 200, s.testPlans().listByProject(actor, project).stream().map(TestPlanResponse::from).toList());
            } else {
                var b = JsonHttp.read(req, CreateTestPlanRequest.class);
                var value = s.testPlans().create(actor, new CreateTestPlanCommand(project, b.name(), b.description(), b.testCaseIds()));
                res.setHeader("Location", req.getContextPath() + "/api/projects/" + project + "/test-plans/" + value.id());
                JsonHttp.write(res, 201, TestPlanResponse.from(value));
            }
            return;
        }
        if (path.length == 5) {
            Long id = JsonHttp.positiveId(path[4]);
            JsonHttp.method(res, method, "GET", "PUT");
            if (method.equals("GET")) {
                JsonHttp.write(res, 200, TestPlanDetailResponse.from(s.testPlans().getDetails(actor, project, id)));
            } else {
                var b = JsonHttp.read(req, UpdateTestPlanRequest.class);
                JsonHttp.write(res, 200, TestPlanResponse.from(s.testPlans().update(actor, project,
                        new UpdateTestPlanCommand(id, b.name(), b.description(), b.status(), b.expectedVersion()))));
            }
            return;
        }
        boolean archive = path.length == 6 && path[5].equals("archive");
        boolean add = path.length == 7 && path[5].equals("test-cases");
        boolean remove = path.length == 8 && path[5].equals("test-cases") && path[7].equals("remove");
        if (archive || add || remove) {
            Long id = JsonHttp.positiveId(path[4]);
            Long caseId = archive ? null : JsonHttp.positiveId(path[6]);
            JsonHttp.method(res, method, "POST");
            var b = JsonHttp.read(req, ExpectedVersionRequest.class);
            if (archive) s.testPlans().archive(actor, project, id, b.expectedVersion());
            else if (add) s.testPlans().addCase(actor, project, id, caseId, b.expectedVersion());
            else s.testPlans().removeCase(actor, project, id, caseId, b.expectedVersion());
            res.setStatus(204);
            return;
        }
        throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
    }
}
