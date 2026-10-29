package com.link.easyai.starter.engine.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 端点鉴权拦截器。
 * <p>
 * 拦截 {@code /easyai/engine/**} 的所有请求，交给 {@link AiEndpointAuthorizer}
 * 判定。默认（框架内置实现）拒绝所有请求并返回 HTTP 403，
 * 集成方实现 {@link AiEndpointAuthorizer} Bean 后按业务放行。
 */
public class EndpointAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(EndpointAuthInterceptor.class);

    private static final String DENY_BODY =
            "{\"success\":false,\"errorCode\":403,\"errorMessage\":\"请求被拒绝：未通过端点鉴权（easy-ai.security）\"}";

    private final AiEndpointAuthorizer authorizer;

    public EndpointAuthInterceptor(AiEndpointAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        EndpointAuthRequest authRequest = EndpointAuthRequest.from(request);
        boolean allowed = authorizer.authorize(authRequest);
        if (allowed) {
            return true;
        }
        log.warn("[EndpointAuth] rejected {} {} tenant={} session={}",
                authRequest.getHttpMethod(), authRequest.getEndpoint(),
                authRequest.getTenantId(), authRequest.getSessionId());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(DENY_BODY.getBytes(StandardCharsets.UTF_8));
        return false;
    }
}
