package io.github.lz007001cn.veriqra.web.security;

import io.github.lz007001cn.veriqra.web.HttpFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SameOriginPolicyTest {
    HttpServletRequest request(String method, String scheme, String host, int port, String remote, Map<String,List<String>> headers) {
        return (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (p,m,a) -> switch(m.getName()) {
                    case "getMethod" -> method; case "getScheme" -> scheme; case "getServerName" -> host;
                    case "getServerPort" -> port; case "getRemoteAddr" -> remote;
                    case "getHeaders" -> Collections.enumeration(headers.getOrDefault((String)a[0], List.of()));
                    default -> throw new AssertionError("Unexpected request attribute " + m.getName());
                });
    }
    Map<String,List<String>> headers(String origin) {
        var h=new HashMap<String,List<String>>(); h.put("Host",List.of("qa.example"));
        h.put("X-Veriqra-Request",List.of("1")); if(origin!=null) h.put("Origin",List.of(origin)); return h;
    }
    @Test void sameOriginWritesAndReadWithoutOriginPass() {
        var policy=new SameOriginPolicy("https://qa.example");
        for(String verb:List.of("POST","PUT","PATCH","DELETE")) policy.check(request(verb,"https","qa.example",443,"client",headers("https://qa.example")));
        policy.check(request("GET","https","qa.example",443,"client",headers(null)));
    }
    @Test void missingOpaqueMalformedAndAttackerOriginsAreRejected() {
        var policy=new SameOriginPolicy("https://qa.example");
        for(String origin:Arrays.asList(null,"null","https://evil.example","https://qa.example.evil","https://qa.example@evil.example",
                "https://qa.example/","http://qa.example","https://qa.example:444","https://qa.example https://evil.example","https://qa.example?x"))
            assertThrows(HttpFailure.class, () -> policy.check(request("POST","https","qa.example",443,"client",headers(origin))));
        var duplicated=headers("https://qa.example"); duplicated.put("Origin",List.of("https://qa.example","https://qa.example"));
        assertThrows(HttpFailure.class, () -> policy.check(request("POST","https","qa.example",443,"client",duplicated)));
    }
    @Test void forgedHostAndForwardedHeadersCannotChooseOriginOrScheme() {
        var policy=new SameOriginPolicy("https://qa.example");
        var h=headers("https://evil.example"); h.put("Host",List.of("evil.example"));
        h.put("X-Forwarded-Host",List.of("qa.example")); h.put("X-Forwarded-Proto",List.of("https"));
        assertThrows(HttpFailure.class, () -> policy.check(request("POST","https","evil.example",443,"client",h)));
        var valid=headers("https://qa.example"); valid.put("X-Forwarded-Proto",List.of("https"));
        assertThrows(HttpFailure.class, () -> policy.check(request("POST","http","qa.example",80,"client",valid)));
    }
    @Test void unsetOriginAllowsOnlyLocalHttpAndInvalidConfigFailsClosed() {
        var p=new SameOriginPolicy(null); var h=Map.of("Host",List.of("127.0.0.1:8080"));
        p.check(request("GET","http","127.0.0.1",8080,"127.0.0.1",h));
        assertThrows(HttpFailure.class, () -> p.check(request("GET","http","127.0.0.1",8080,"203.0.113.1",h)));
        for(String v:List.of("", "http://qa.example", "https://qa.example/", "https://qa.example:0", "https://qa.example:"))
            assertThrows(IllegalArgumentException.class, () -> new SameOriginPolicy(v));
    }
    @Test void markerMustBeCanonicalExactAndUnique() {
        var policy = new SameOriginPolicy("https://qa.example");
        var h = headers("https://qa.example");
        for (var values : List.of(List.of("0"), List.of(""), List.of("1", "1"), List.of("1, 1"))) {
            h.put("X-Veriqra-Request", values);
            assertThrows(HttpFailure.class, () -> policy.check(request("POST", "https", "qa.example", 443, "client", h)));
        }
        h.remove("X-Veriqra-Request");
        assertThrows(HttpFailure.class, () -> policy.check(request("POST", "https", "qa.example", 443, "client", h)));
        h.put("X-Veriqra-Request", List.of("1")); h.put("Origin", List.of("https://evil.example"));
        assertThrows(HttpFailure.class, () -> policy.check(request("POST", "https", "qa.example", 443, "client", h)));
    }
}
