package io.github.lz007001cn.veriqra.admin;

import java.util.Locale;

/** Coarse local classification, not a fingerprint. Never examines hardware or forwarded headers. */
public record UserAgentInfo(String raw,String browser,String operatingSystem,String deviceType) {
    public static UserAgentInfo from(String header) {
        String raw=header==null?"":header.replaceAll("[\\x00-\\x1F\\x7F]"," ");
        if(raw.length()>512)raw=raw.substring(0,512);
        String s=raw.toLowerCase(Locale.ROOT);
        String browser=s.contains("edg/")?"Edge":s.contains("firefox/")?"Firefox"
                :s.contains("chrome/")?"Chrome":s.contains("safari/")?"Safari":"Other";
        String os=s.contains("android")?"Android":s.contains("iphone")||s.contains("ipad")?"iOS"
                :s.contains("windows")?"Windows":s.contains("mac os")||s.contains("macintosh")?"macOS"
                :s.contains("linux")?"Linux":"Other";
        String device=s.contains("ipad")||s.contains("tablet")?"Tablet"
                :s.contains("mobile")||s.contains("iphone")||s.contains("android")?"Mobile"
                :s.isBlank()?"Other":"Desktop";
        return new UserAgentInfo(raw,browser,os,device);
    }
}
