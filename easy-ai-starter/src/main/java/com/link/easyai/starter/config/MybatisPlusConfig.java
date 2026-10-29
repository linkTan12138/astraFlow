package com.link.easyai.starter.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 分页插件配置。
 * <p>
 * 宿主项目若已自行注册 {@link MybatisPlusInterceptor}（如已有分页插件），
 * 本框架自动跳过注册，避免两个分页插件叠加导致分页参数冲突。
 * 可通过 {@code easy-ai.mybatis-plus-enabled=false} 完全关闭本配置。
 */
@Configuration
@ConditionalOnProperty(prefix = "easy-ai", name = "mybatis-plus-enabled",
        havingValue = "true", matchIfMissing = true)
public class MybatisPlusConfig {

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
