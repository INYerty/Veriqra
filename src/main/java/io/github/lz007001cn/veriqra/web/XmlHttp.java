package io.github.lz007001cn.veriqra.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;

/** Strict transport rules for raw JUnit XML uploads. */
public final class XmlHttp {
    public static final int MAX_BODY = 5 * 1024 * 1024;
    private XmlHttp() { }

    public static byte[] read(HttpServletRequest request) throws IOException {
        String contentType = request.getContentType();
        if (contentType == null || !contentType.split(";", 2)[0].strip().equalsIgnoreCase("application/xml")) {
            throw new HttpFailure(415, "UNSUPPORTED_MEDIA_TYPE", "Expected application/xml");
        }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) {
            throw new HttpFailure(413, "PAYLOAD_TOO_LARGE", "XML body exceeds 5 MiB");
        }
        return bytes;
    }

    public static String requiredParameter(HttpServletRequest request, String name) {
        String value = singleParameter(request, name);
        if (value == null || value.isBlank()) {
            throw new HttpFailure(400, "INVALID_PARAMETER", name + " is required");
        }
        return value;
    }

    public static String optionalParameter(HttpServletRequest request, String name) {
        String value = singleParameter(request, name);
        return value == null || value.isBlank() ? null : value;
    }

    public static UUID requiredUuidParameter(HttpServletRequest request, String name) {
        try {
            return UUID.fromString(requiredParameter(request, name));
        } catch (IllegalArgumentException failure) {
            throw new HttpFailure(400, "INVALID_PARAMETER", name + " must be a UUID");
        }
    }

    private static String singleParameter(HttpServletRequest request, String name) {
        String[] values = request.getParameterValues(name);
        if (values == null) return null;
        if (values.length != 1) {
            throw new HttpFailure(400, "INVALID_PARAMETER", name + " must be supplied once");
        }
        return values[0];
    }
}
