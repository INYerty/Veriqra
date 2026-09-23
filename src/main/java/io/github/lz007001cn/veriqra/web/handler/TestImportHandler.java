package io.github.lz007001cn.veriqra.web.handler;

import io.github.lz007001cn.veriqra.service.command.*;
import io.github.lz007001cn.veriqra.web.*;
import io.github.lz007001cn.veriqra.web.dto.*;
import jakarta.servlet.http.*;
import java.io.IOException;

public final class TestImportHandler {
    private TestImportHandler() { }

    public static void handle(HttpServletRequest req, HttpServletResponse res, WebServices services,
                              Long actor, Long projectId, String[] path) throws IOException {
        JsonHttp.method(res, req.getMethod(), "POST");
        if (path.length == 5 && path[4].equals("preview")) {
            byte[] payload = XmlHttp.read(req);
            var preview = services.imports().analyzeImport(actor,
                    new AnalyzeTestImportCommand(projectId,
                            XmlHttp.requiredParameter(req, "sourceNamespace"), payload));
            JsonHttp.write(res, 200, ImportPreviewResponse.from(preview));
            return;
        }
        if (path.length == 4) {
            byte[] payload = XmlHttp.read(req);
            var result = services.imports().importReport(actor, new ImportTestResultsCommand(projectId,
                    XmlHttp.requiredUuidParameter(req, "requestKey"),
                    XmlHttp.requiredParameter(req, "sourceNamespace"),
                    XmlHttp.requiredParameter(req, "originalFilename"),
                    XmlHttp.requiredParameter(req, "runName"),
                    XmlHttp.optionalParameter(req, "environment"),
                    XmlHttp.optionalParameter(req, "buildVersion"), payload));
            res.setHeader("Location", req.getContextPath() + "/api/projects/" + projectId + "/runs/"
                    + result.testRun().id());
            JsonHttp.write(res, result.replayed() ? 200 : 201, ImportExecutionResponse.from(result));
            return;
        }
        throw new HttpFailure(404, "NOT_FOUND", "Resource not found");
    }
}
