package com.link.easyai.starter.engine.security;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 端点鉴权请求上下文。
 * <p>
 * 由 {@link EndpointAuthInterceptor} 从 HTTP 请求构造后交给
 * {@link AiEndpointAuthorizer} 判断是否放行。
 * <p>
 * 注意：POST body 中的字段不会被解析（拦截器不消费请求体），
 * 鉴权凭据（tenantId / sessionId / taskType）应从
 * {@code X-Tenant-Id}、{@code X-Session-Id}、{@code X-Task-Type} 请求头
 * 或 URL 查询参数中传递。
 */
public final class EndpointAuthRequest {

    private final String httpMethod;
    private final String endpoint;
    private final String tenantId;
    private final String sessionId;
    private final String taskType;
    private final Map<String, String> queryParams;
    private final Map<String, String> headers;

    public EndpointAuthRequest(String httpMethod, String endpoint, String tenantId,
                               String sessionId, String taskType,
                               Map<String, String> queryParams, Map<String, String> headers) {
        this.httpMethod = httpMethod;
        this.endpoint = endpoint;
        this.tenantId = tenantId;
        this.sessionId = sessionId;
        this.taskType = taskType;
        this.queryParams = queryParams != null ? queryParams : Collections.emptyMap();
        this.headers = headers != null ? headers : Collections.emptyMap();
    }

    public static EndpointAuthRequest from(HttpServletRequest request) {
        Map<String, String> query = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) ->
                query.put(k, v != null && v.length > 0 ? v[0] : null));

        Map<String, String> headerMap = new LinkedHashMap<>();
        for (String name : new String[]{"X-Tenant-Id", "X-Session-Id", "X-Task-Type", "Authorization"}) {
            String value = request.getHeader(name);
            if (value != null) {
                headerMap.put(name, value);
            }
        }

        String tenantId = firstNonBlank(request.getHeader("X-Tenant-Id"), query.get("tenantId"));
        String sessionId = firstNonBlank(request.getHeader("X-Session-Id"), query.get("sessionId"));
        String taskType = firstNonBlank(request.getHeader("X-Task-Type"), query.get("taskType"));

        return new EndpointAuthRequest(request.getMethod(), request.getRequestURI(),
                tenantId, sessionId, taskType, query, headerMap);
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    public String getHttpMethod() { return httpMethod; }
    public String getEndpoint() { return endpoint; }
    public String getTenantId() { return tenantId; }
    public String getSessionId() { return sessionId; }
    public String getTaskType() { return taskType; }
    public Map<String, String> getQueryParams() { return queryParams; }
    public Map<String, String> getHeaders() { return headers; }

    @Override
    public String toString() {
        return "EndpointAuthRequest{" + httpMethod + " " + endpoint +
                ", tenant=" + tenantId + ", session=" + sessionId + ", taskType=" + taskType + "}";
    }
}
