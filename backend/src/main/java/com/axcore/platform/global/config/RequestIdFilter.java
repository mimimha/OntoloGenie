package com.axcore.platform.global.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청마다 추적용 requestId를 만들어 로그(MDC), 응답 헤더, 오류 응답에 같은 값으로 남긴다. 클라이언트가 보낸 값은 쓰지 않는다. Action 실행 요청 본문의
 * requestId(중복 실행 방지 키)와는 다른 값이다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    /** 이 요청의 requestId. 필터를 거치지 않은 요청이면 null이다. */
    public static String currentRequestId(HttpServletRequest request) {
        return (String) request.getAttribute(ATTRIBUTE);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_KEY, requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
