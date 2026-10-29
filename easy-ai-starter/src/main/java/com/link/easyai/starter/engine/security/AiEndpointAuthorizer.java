package com.link.easyai.starter.engine.security;

/**
 * 端点鉴权 SPI（服务提供者接口）。
 * <p>
 * 框架默认实现为<b>拒绝所有请求</b>（{@code easy-ai.security-enabled=true} 时）。
 * 集成方只需在自身工程中提供一个 {@code AiEndpointAuthorizer} 的 Spring Bean
 * 即可覆盖默认实现，按业务需要放行 / 拒绝 {@code /easyai/engine/**} 的调用。
 * <p>
 * 实现示例（放行一切，仅用于测试）：
 * <pre>{@code
 * @Component
 * public class PermitAllAuthorizer implements AiEndpointAuthorizer {
 *     @Override
 *     public boolean authorize(EndpointAuthRequest request) {
 *         return true;
 *     }
 * }
 * }</pre>
 */
public interface AiEndpointAuthorizer {

    /**
     * 判断是否放行该端点请求。
     *
     * @param request 鉴权上下文（httpMethod / endpoint / tenantId / sessionId / taskType / 请求头）
     * @return true = 放行；false = 拒绝（返回 HTTP 403）
     */
    boolean authorize(EndpointAuthRequest request);
}
