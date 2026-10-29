package com.link.easyai.starter.engine.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 端点鉴权自动配置。
 * <p>
 * 拦截 {@code /easyai/engine/**} 全部端点（含配置管理、直连执行、SSE 流式）。
 * 默认提供 {@link #denyAllEndpointAuthorizer()}：<b>拒绝所有请求</b>。
 * 集成方提供自己的 {@link AiEndpointAuthorizer} Bean 即自动覆盖（安全优先）。
 * <p>
 * 可通过 {@code easy-ai.security-enabled=false} 完全关闭鉴权（不推荐生产使用）。
 */
@Configuration
@ConditionalOnProperty(prefix = "easy-ai", name = "security-enabled",
        havingValue = "true", matchIfMissing = true)
public class EngineSecurityConfiguration implements WebMvcConfigurer {

    private final AiEndpointAuthorizer authorizer;

    public EngineSecurityConfiguration(AiEndpointAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    /**
     * 默认鉴权实现：拒绝所有请求。集成方实现同名 Bean 后自动替换。
     */
    @Bean
    @ConditionalOnMissingBean(AiEndpointAuthorizer.class)
    public AiEndpointAuthorizer denyAllEndpointAuthorizer() {
        return request -> false;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new EndpointAuthInterceptor(authorizer))
                .addPathPatterns("/easyai/engine/**");
    }
}
