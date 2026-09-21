package io.github.lz007001cn.qatrack.web.security;

import io.github.lz007001cn.qatrack.web.HttpFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Collections;
import java.util.Locale;

/** Uses container request attributes only; never interprets forwarded headers. */
public final class SameOriginPolicy {
    private final Origin configured;

    public SameOriginPolicy(String publicOrigin) {
        try {
            configured = publicOrigin == null ? null : Origin.parse(publicOrigin);
            if (configured != null && !configured.scheme.equals("https")) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("QATRACK_PUBLIC_ORIGIN must be one HTTPS origin without a path");
        }
    }

    public void check(HttpServletRequest request) {
        try {
            String host = singleHeader(request, "Host");
            Origin target = Origin.parse(request.getScheme() + "://" + host);
            String serverName = request.getServerName();
            if (serverName.contains(":") && !serverName.startsWith("[")) serverName = "[" + serverName + "]";
            Origin effective = Origin.parse(request.getScheme() + "://" + serverName
                    + ":" + request.getServerPort());
            if (!target.equals(effective)) throw new IllegalArgumentException();
            if (configured != null) {
                if (!configured.equals(target)) throw new IllegalArgumentException();
            } else if (!target.scheme.equals("http") || !isLoopback(target.host)
                    || !isLoopback(request.getRemoteAddr())) {
                // Unconfigured applications support loopback development only, never public deployment.
                throw new IllegalArgumentException();
            }
            if (!request.getMethod().equals("GET") && !request.getMethod().equals("HEAD")
                    && !request.getMethod().equals("OPTIONS")) {
                if (!target.equals(Origin.parse(singleHeader(request, "Origin")))) throw new IllegalArgumentException();
                if (!"1".equals(singleHeader(request, "X-QATrack-Request"))) throw new IllegalArgumentException();
            }
        } catch (IllegalArgumentException e) {
            throw new HttpFailure(403, "ORIGIN_REJECTED", "Request origin is not permitted");
        }
    }

    private static String singleHeader(HttpServletRequest request, String name) {
        var values = Collections.list(request.getHeaders(name));
        if (values.size() != 1) throw new IllegalArgumentException();
        return values.getFirst();
    }

    private static boolean isLoopback(String host) {
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1")
                || host.equals("[::1]") || host.equals("0:0:0:0:0:0:0:1");
    }

    private record Origin(String scheme, String host, int port) {
        static Origin parse(String value) {
            if (value == null || value.length() > 512) throw new IllegalArgumentException();
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.getRawPath().isEmpty() || uri.getPort() < -1 || uri.getPort() == 0
                    || uri.getPort() > 65535 || uri.getRawAuthority().endsWith(":")) throw new IllegalArgumentException();
            return new Origin(scheme, uri.getHost().toLowerCase(Locale.ROOT),
                    uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort());
        }
    }
}
