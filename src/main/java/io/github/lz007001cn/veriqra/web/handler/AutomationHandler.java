package io.github.lz007001cn.veriqra.web.handler;

import io.github.lz007001cn.veriqra.web.*;
import io.github.lz007001cn.veriqra.web.dto.*;
import jakarta.servlet.http.*;
import java.io.IOException;

public final class AutomationHandler {
    private AutomationHandler() { }

    public static void handleProject(HttpServletRequest req, HttpServletResponse res, WebServices services,
                                     Long actor, Long projectId, String[] path) throws IOException {
        if (path.length != 5) notFound();
        switch (path[4]) {
            case "identities" -> {
                JsonHttp.method(res, req.getMethod(), "GET", "POST");
                if (req.getMethod().equals("GET")) {
                    JsonHttp.write(res, 200, services.automation().listIdentities(actor, projectId).stream()
                            .map(AutomationIdentityResponse::from).toList());
                } else {
                    var body = JsonHttp.read(req, RegisterAutomationIdentityRequest.class);
                    var value = services.automation().registerIdentity(actor, projectId, body.source(),
                            body.namespace(), body.externalKey());
                    res.setHeader("Location", req.getContextPath() + "/api/automation/identities/" + value.id());
                    JsonHttp.write(res, 201, AutomationIdentityResponse.from(value));
                }
            }
            case "mappings" -> {
                JsonHttp.method(res, req.getMethod(), "GET");
                JsonHttp.write(res, 200, services.automation().listMappings(actor, projectId).stream()
                        .map(AutomationMappingResponse::from).toList());
            }
            default -> notFound();
        }
    }

    public static void handle(HttpServletRequest req, HttpServletResponse res, WebServices services,
                              Long actor, String[] path) throws IOException {
        if (path.length < 4 || path.length > 6) notFound();
        long identityId = JsonHttp.positiveId(path[3]);
        if (path.length == 4) {
            JsonHttp.method(res, req.getMethod(), "GET");
            JsonHttp.write(res, 200, AutomationIdentityResponse.from(services.automation().getIdentity(actor, identityId)));
            return;
        }
        if (!path[4].equals("mapping")) notFound();
        if (path.length == 5) {
            JsonHttp.method(res, req.getMethod(), "GET", "PUT");
            if (req.getMethod().equals("GET")) {
                var mapping = services.automation().getCurrentMapping(actor, identityId)
                        .orElseThrow(() -> new HttpFailure(404, "NOT_FOUND", "Active automation mapping does not exist"));
                JsonHttp.write(res, 200, AutomationMappingResponse.from(mapping));
            } else {
                var body = JsonHttp.read(req, MapAutomationIdentityRequest.class);
                JsonHttp.write(res, 200, AutomationMappingResponse.from(services.automation()
                        .mapIdentity(actor, identityId, body.testCaseId(), body.expectedVersion())));
            }
            return;
        }
        if (path[5].equals("deactivate")) {
            JsonHttp.method(res, req.getMethod(), "POST");
            var body = JsonHttp.read(req, ExpectedVersionRequest.class);
            JsonHttp.write(res, 200, AutomationMappingResponse.from(services.automation()
                    .deactivateMapping(actor, identityId, body.expectedVersion())));
            return;
        }
        notFound();
    }

    private static void notFound() { throw new HttpFailure(404, "NOT_FOUND", "Resource not found"); }
}
