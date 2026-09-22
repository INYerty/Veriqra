package io.github.lz007001cn.veriqra.web.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import io.github.lz007001cn.veriqra.web.SessionIdentity;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionCookiePolicyTest {
    @Test void productionRequiresSecureAndSetsTimeoutPathAndCookieFlags() {
        var actual=new HashMap<String,Object>();
        var cookie=(SessionCookieConfig) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{SessionCookieConfig.class},
                (p,m,a)->{actual.put(m.getName(),a.length==1?a[0]:List.of(a));return null;});
        var context=(ServletContext) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ServletContext.class},
                (p,m,a)->switch(m.getName()) {
                    case "getSessionCookieConfig" -> cookie; case "getContextPath" -> "/veriqra";
                    case "setSessionTimeout" -> {actual.put("timeout",a[0]);yield null;}
                    default -> throw new AssertionError(m.getName());
                });
        assertThrows(IllegalStateException.class,()->SessionCookiePolicy.configure(context,"false","https://qa.example"));
        assertThrows(IllegalStateException.class,()->SessionCookiePolicy.configure(context,null,"https://qa.example"));
        assertThrows(IllegalStateException.class,()->SessionCookiePolicy.configure(context,"TRUE",null));
        SessionCookiePolicy.configure(context,"true","https://qa.example");
        assertEquals(true,actual.get("setSecure")); assertEquals(true,actual.get("setHttpOnly"));
        assertEquals("/veriqra",actual.get("setPath")); assertEquals(30,actual.get("timeout"));
        assertEquals(List.of("SameSite","Lax"),actual.get("setAttribute"));
    }
    @Test void loginSessionHasThirtyMinuteIdleTimeout() {
        var actual=new HashMap<String,Object>();
        var session=(HttpSession) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpSession.class},
                (p,m,a)->{actual.put(m.getName(),a.length==1?a[0]:List.of(a));return null;});
        var req=(HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServletRequest.class},
                (p,m,a)->{if(m.getName().equals("getSession"))return (boolean)a[0]?session:null;throw new AssertionError(m.getName());});
        SessionIdentity.login(req,1L);
        assertEquals(1800,actual.get("setMaxInactiveInterval"));
        assertTrue(actual.containsKey("setAttribute"));
    }
}
