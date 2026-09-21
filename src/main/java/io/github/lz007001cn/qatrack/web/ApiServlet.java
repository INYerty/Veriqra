package io.github.lz007001cn.qatrack.web;

import io.github.lz007001cn.qatrack.service.command.*;
import io.github.lz007001cn.qatrack.web.dto.*;
import io.github.lz007001cn.qatrack.web.handler.*;
import jakarta.servlet.http.*;
import java.io.IOException;

/** Small explicit route table; all business work is delegated to Service interfaces. */
public final class ApiServlet extends HttpServlet {
    @Override protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        WebServices services = WebServices.from(getServletContext());
        String path = request.getPathInfo() == null ? "" : request.getPathInfo();
        String method = request.getMethod();
        switch (path) {
            case "/auth/login" -> {
                method(response, method, "POST");
                LoginRequest body = JsonHttp.read(request, LoginRequest.class);
                var user = services.auth().authenticate(body.username(), body.password());
                SessionIdentity.login(request, user.id());
                JsonHttp.write(response, 200, UserResponse.from(user));
            }
            case "/auth/logout" -> {
                method(response, method, "POST");
                SessionIdentity.logout(request);
                response.setStatus(204);
            }
            case "/auth/me" -> {
                method(response, method, "GET");
                JsonHttp.write(response, 200, UserResponse.from(services.auth().current(SessionIdentity.require(request))));
            }
            case "/projects" -> {
                Long actor = SessionIdentity.require(request);
                if (method.equals("GET")) {
                    JsonHttp.write(response, 200, services.projects().list(actor).stream().map(ProjectResponse::from).toList());
                } else {
                    method(response, method, "GET", "POST");
                    CreateProjectRequest body = JsonHttp.read(request, CreateProjectRequest.class);
                    var project = services.projects().create(actor,
                            new CreateProjectCommand(body.projectKey(), body.name(), body.description(), null, null));
                    response.setHeader("Location", request.getContextPath() + "/api/projects/" + project.id());
                    JsonHttp.write(response, 201, ProjectResponse.from(project));
                }
            }
            default -> projectRoute(request, response, services, path, method);
        }
    }

    private void projectRoute(HttpServletRequest request, HttpServletResponse response, WebServices services,
                              String path, String method) throws IOException {
        String[] parts = path.split("/", -1);
        if (parts.length >= 3 && parts[1].equals("projects")) {
            long projectId = JsonHttp.positiveId(parts[2]);
            Long actor = SessionIdentity.require(request);
            if (parts.length == 3) {
                method(response, method, "GET");
                JsonHttp.write(response, 200, ProjectResponse.from(services.projects().get(actor, projectId)));
                return;
            }
            switch (parts[3]) {
                case "runs" -> TestRunHandler.handle(request, response, services, actor, projectId, parts);
                case "defects" -> DefectHandler.handle(request, response, services, actor, projectId, parts);
                case "requirements" -> RequirementHandler.handle(request, response, services, actor, projectId, parts);
                case "test-cases" -> TestCaseHandler.handle(request, response, services, actor, projectId, parts);
                case "test-plans" -> TestPlanHandler.handle(request, response, services, actor, projectId, parts);
                default -> throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
            }
            return;
        }
        throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
    }

    private static void method(HttpServletResponse response, String actual, String... allowed) {
        JsonHttp.method(response, actual, allowed);
    }
}
