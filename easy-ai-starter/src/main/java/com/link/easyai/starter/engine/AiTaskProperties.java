package com.link.easyai.starter.engine;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the AI Task Engine.
 * <p>
 * Example application.yml:
 * <pre>
 * easy-ai:
 *   task-engine:
 *     enabled: true
 *     config-source: database   # database | json
 *     json-config-path: classpath:ai-task-configs/
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "easy-ai.task-engine")
public class AiTaskProperties {

    /** Whether to enable the AI Task Engine */
    private boolean enabled = true;

    /** Config source: "database" or "json" */
    private String configSource = "database";

    /** Path for JSON config files (when configSource=json) */
    private String jsonConfigPath = "classpath:ai-task-configs/";

    /** Whether to enable trace logging */
    private boolean traceEnabled = true;

    /** Annotation-based config source (code-defined tasks via @AiTask DTOs) */
    private final Annotation annotation = new Annotation();

    /** LLM engineering: retry, fallback, timeout, etc. */
    private final Llm llm = new Llm();

    /** Conversation lifecycle: max turns, timeout, etc. */
    private final Lifecycle lifecycle = new Lifecycle();

    /** Snowflake ID generator configuration (workerId / datacenterId). */
    private final Snowflake snowflake = new Snowflake();

    /** LLM resilience configuration (rate limiting, circuit breaking). */
    private final Resilience resilience = new Resilience();

    @Data
    public static class Annotation {

        /**
         * Whether to enable the annotation config source. When enabled, a
         * taskType declared via @AiTask is always served from code (version 1)
         * and shadowed database configs are ignored with a warning.
         */
        private boolean enabled = true;

        /**
         * Base packages to scan for @AiTask classes. Defaults to the Spring
         * Boot application package (AutoConfigurationPackages) when empty.
         */
        private String[] basePackages = {};
    }

    @Data
    public static class Llm {

        /** Maximum retry attempts for transient failures (timeout, 5xx, 429). */
        private int maxRetries = 3;

        /** Initial backoff delay in milliseconds for exponential backoff. */
        private long initialBackoffMs = 1000;

        /** Backoff multiplier for exponential backoff. */
        private double backoffMultiplier = 2.0;

        /**
         * Fallback model names in priority order. When the primary model fails
         * after maxRetries, the client tries each fallback in order.
         * Example: ["doubao", "deepseek"]
         */
        private String[] fallbackModels = {};

        /** Maximum input length for user messages (characters). Longer inputs are truncated. */
        private int maxInputLength = 2000;

        /**
         * 单次 LLM 调用超时（秒）。同时覆盖连接超时与读取超时，
         * 防止网络故障导致请求线程无限挂死。默认 60。
         */
        private int timeoutSeconds = 60;

        /** Whether to enable prompt injection detection. */
        private boolean injectionDetectionEnabled = true;

        /**
         * 提示词注入检测到可疑消息时是否直接阻断（返回固定提示）。
         * false = 仅记录告警日志，消息继续进入管道（默认，保持向后兼容）；
         * true  = 检测命中即拦截，不进入意图识别与参数提取。
         */
        private boolean injectionBlockEnabled = false;

        /**
         * 是否开启 LLM 调用详细日志。
         * 开启后每次 LLM 调用都会输出完整的请求消息（system + user）和响应内容，
         * 便于调试提示词和排查问题。生产环境建议关闭。
         */
        private boolean logEnabled = false;

        /**
         * 意图识别专用模型（llm.providers 注册表 key 或完整类名）。
         * 缺省（null/空）时回退到全局 llm.provider。
         * 配置示例：intent-provider: kimi
         */
        private String intentProvider;

        /**
         * 参数提取（字段收集）专用模型（llm.providers 注册表 key 或完整类名）。
         * 缺省（null/空）时回退到全局 llm.provider。
         * 配置示例：extraction-provider: qwen
         */
        private String extractionProvider;
    }

    @Data
    public static class Lifecycle {

        /** Maximum conversation turns per task. Exceeding marks the task FAILED. */
        private int maxTurns = 10;

        /** Task timeout in minutes. Expired tasks are marked EXPIRED. */
        private int timeoutMinutes = 30;

        /**
         * 是否启用框架后台定时任务（任务过期标记 + 过期分布式锁清理）。
         * 默认启用。宿主可置 false 完全关闭本框架的调度线程，
         * 避免与宿主导航自己的调度任务冲突。
         */
        private boolean schedulerEnabled = true;

        /**
         * 是否启用后台定时任务，主动将超过 timeout-minutes 未更新的处理中任务标记为 EXPIRED。
         * 默认启用。禁用后任务过期只能依赖会话层面的懒清理（用户下次访问时触发）。
         */
        private boolean expireEnabled = true;

        /**
         * 定时过期检查的固定延迟（毫秒）。默认 600000（10 分钟）。
         */
        private long expireIntervalMs = 600000L;
    }

    @Data
    public static class Snowflake {

        /**
         * 工作机器ID (0-31)。多实例部署时每个实例应配置不同的 workerId，
         * 否则可能产生重复ID。默认0，单实例部署无需修改。
         */
        private long workerId = 0L;

        /**
         * 数据中心ID (0-31)。跨数据中心部署时每个数据中心配置不同的 datacenterId。
         * 默认0。
         */
        private long datacenterId = 0L;
    }

    @Data
    public static class Resilience {

        /** 是否启用限流熔断保护。默认启用。 */
        private boolean enabled = true;

        /**
         * 限流配置：每秒允许的最大请求数（QPS）。
         * 超过此限制的请求将快速失败，避免LLM服务被打垮。默认10。
         */
        private int rateLimitPerSecond = 10;

        /**
         * 限流时间窗口（秒）。默认1秒。
         */
        private int rateLimitWindowSeconds = 1;

        /**
         * 熔断配置：连续失败多少次后打开熔断器（按模型独立计数）。默认20。
         * 打开后快速失败 waitDurationInOpenStateSeconds 秒，随后自动恢复。
         */
        private int circuitBreakerSlidingWindowSize = 20;

        /**
         * 熔断打开后自动恢复等待时间（秒）。默认30。
         */
        private int circuitBreakerWaitDurationInOpenStateSeconds = 30;
    }
}
