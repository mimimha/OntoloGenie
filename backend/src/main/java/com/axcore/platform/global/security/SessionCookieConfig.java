package com.axcore.platform.global.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 세션 쿠키 속성 (API 001). JavaScript가 읽지 못하게 하고(HttpOnly), HTTPS로만 보내고(Secure), 다른 사이트에서 온 요청에는 붙지 않게
 * 한다(SameSite=Lax). server.servlet.session.cookie 설정은 Spring Session 쿠키에 적용되지 않아 코드로 정한다.
 */
@Configuration(proxyBeanMethods = false)
public class SessionCookieConfig {

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        serializer.setSameSite("Lax");
        return serializer;
    }
}
