package com.link.easyai.starter.engine.llm;

import com.link.easyai.starter.engine.AiTaskProperties;
import com.link.easyai.starter.llm.LLMConfig;
import com.link.easyai.starter.llm.LLMProvider;
import com.link.easyai.starter.llm.LLMProviderFactory;
import com.link.easyai.starter.llm.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Resilient LLM client: rate limiting (global sliding window),
 * per-model circuit breaking, retry with exponential backoff,
 * fallback chain. 异常分类基于 HTTP 状态码（LlmCallException.statusCode），
 * 不再依赖字符串匹配。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    private final LLMProviderFactory llmFactory;
    private final LLMConfig llmConfig;
    private final AiTaskProperties properties;

    // ---- 限流：滑动时间窗口（全局） ----
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger windowRequestCount = new AtomicInteger(0);

    // ---- 熔断：按模型分桶，主模型熔断不连坐 fallback ----
    private final ConcurrentHashMap<String, ModelCircuit> circuits = new ConcurrentHashMap<>();

    @Autowired
    public LlmClient(LLMProviderFactory llmFactory, LLMConfig llmConfig, AiTaskProperties properties) {
        this.llmFactory = llmFactory;
        this.llmConfig = llmConfig;
        this.properties = properties;
        log.info("[LlmClient] initialized with built-in rate limiting + per-model circuit breaking");
    }

    /** Call the LLM with retry + fallback. */
    public String chatCompletion(String primary, String system, String user) {
        AiTaskProperties.Llm config = properties.getLlm();
        List<String> models = buildModelChain(primary, config.getFallbackModels());

        Exception lastException = null;
        for (String model : models) {
            try {
                return callWithRetry(model, system, user, config);
            } catch (LlmCallException e) {
                lastException = e;
                if (e.isNonRetryable()) {
                    log.warn("[LlmClient] model '{}' non-retryable failure (HTTP={}), skipping fallback: {}",
                            model, e.getStatusCode(), e.getMessage());
                    break;
                }
                log.warn("[LlmClient] model '{}' failed after retries (HTTP={}): {}",
                        model, e.getStatusCode(), e.getMessage());
            }
        }

        String msg = "All models failed. Last error: " +
                (lastException != null ? lastException.getMessage() : "unknown");
        throw new LlmCallException(msg, false);
    }

    private List<String> buildModelChain(String primary, String[] fallbacks) {
        List<String> chain = new ArrayList<>();
        chain.add(primary);
        if (fallbacks != null) {
            for (String fb : fallbacks) {
                if (fb != null && !fb.isBlank() && !fb.equals(primary)) {
                    chain.add(fb);
                }
            }
        }
        return chain;
    }

    private String callWithRetry(String modelName, String system, String user,
                                  AiTaskProperties.Llm config) {
        checkCircuitBreaker(modelName);
        checkRateLimit();

        LLMProvider model = resolveModel(modelName);
        int maxRetries = config.getMaxRetries();
        long backoff = config.getInitialBackoffMs();

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                log.debug("[LlmClient] calling model '{}' (attempt {}/{})", modelName, attempt, maxRetries);
                List<Message> messages = new ArrayList<>();
                if (system != null && !system.isBlank()) {
                    messages.add(Message.system(system));
                }
                messages.add(Message.user(user));
                // log-enabled 开关：打印本次 LLM 请求（system + user），便于调试提示词
                if (config != null && config.isLogEnabled()) {
                    log.info("[LLM 请求] [model={}] [system] {}\n[user] {}", modelName,
                            system != null ? system : "", user != null ? user : "");
                }
                String response = model.chat(messages, llmConfig);
                if (config != null && config.isLogEnabled()) {
                    log.info("[LLM 响应] [model={}] {}", modelName,
                            response != null ? response : "<empty>");
                }
                if (response == null || response.isBlank()) {
                    throw new LlmCallException("Model returned empty response", true);
                }
                resetCircuit(modelName);
                return response;
            } catch (LlmCallException e) {
                recordFailure(modelName);
                if (e.isNonRetryable()) {
                    throw e;
                }
                if (attempt < maxRetries) {
                    log.warn("[LlmClient] model '{}' attempt {} failed, retrying in {}ms: {}",
                            modelName, attempt, backoff, e.getMessage());
                    sleep(backoff);
                    backoff = (long) (backoff * config.getBackoffMultiplier());
                } else {
                    log.error("[LlmClient] model '{}' failed after {} attempts: {}",
                            modelName, maxRetries, e.getMessage());
                    throw e;
                }
            } catch (Exception e) {
                recordFailure(modelName);
                LlmCallException classified = classifyException(e);
                if (classified.isNonRetryable()) {
                    throw classified;
                }
                if (attempt < maxRetries) {
                    log.warn("[LlmClient] model '{}' attempt {} failed ({}), retrying in {}ms",
                            modelName, attempt, classified.getMessage(), backoff);
                    sleep(backoff);
                    backoff = (long) (backoff * config.getBackoffMultiplier());
                } else {
                    log.error("[LlmClient] model '{}' failed after {} attempts: {}",
                            modelName, maxRetries, classified.getMessage());
                    throw classified;
                }
            }
        }
        throw new LlmCallException("Unexpected retry loop exit", false);
    }

    // ---- 限流 ----

    private void checkRateLimit() {
        AiTaskProperties.Resilience cfg = properties.getResilience();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        int maxRequests = cfg.getRateLimitPerSecond();
        long windowMs = cfg.getRateLimitWindowSeconds() * 1000L;

        long now = System.currentTimeMillis();
        long windowStart = windowStartTime.get();

        if (now - windowStart > windowMs) {
            if (windowStartTime.compareAndSet(windowStart, now)) {
                windowRequestCount.set(0);
            }
        }

        int count = windowRequestCount.incrementAndGet();
        if (count > maxRequests) {
            windowRequestCount.decrementAndGet();
            log.warn("[LlmClient] rate limit exceeded: {}/{} requests in {}ms",
                    count, maxRequests, windowMs);
            throw new LlmCallException("请求过于频繁，已触发限流保护", false);
        }
    }

    // ---- 熔断：按模型独立分桶 ----

    private static final class ModelCircuit {
        final AtomicInteger failures = new AtomicInteger(0);
        final AtomicLong openUntil = new AtomicLong(0);
    }

    private ModelCircuit circuitOf(String modelName) {
        return circuits.computeIfAbsent(modelName, k -> new ModelCircuit());
    }

    private void checkCircuitBreaker(String modelName) {
        AiTaskProperties.Resilience cfg = properties.getResilience();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        ModelCircuit circuit = circuitOf(modelName);
        if (circuit.openUntil.get() > System.currentTimeMillis()) {
            log.warn("[LlmClient] circuit breaker for model '{}' is OPEN, rejecting request fast", modelName);
            throw new LlmCallException("LLM服务暂不可用（熔断器已打开），请稍后重试", false);
        }
    }

    private void recordFailure(String modelName) {
        AiTaskProperties.Resilience cfg = properties.getResilience();
        if (cfg == null || !cfg.isEnabled()) {
            return;
        }
        ModelCircuit circuit = circuitOf(modelName);
        int failures = circuit.failures.incrementAndGet();
        int threshold = cfg.getCircuitBreakerSlidingWindowSize();
        if (failures >= threshold) {
            long openDuration = cfg.getCircuitBreakerWaitDurationInOpenStateSeconds() * 1000L;
            circuit.openUntil.set(System.currentTimeMillis() + openDuration);
            log.warn("[LlmClient] circuit breaker for model '{}' OPENED after {} consecutive failures, " +
                    "will recover in {}ms", modelName, failures, openDuration);
        }
    }

    private void resetCircuit(String modelName) {
        ModelCircuit circuit = circuits.get(modelName);
        if (circuit != null) {
            circuit.failures.set(0);
            circuit.openUntil.set(0);
        }
    }

    // ---- 辅助 ----

    private LLMProvider resolveModel(String modelName) {
        return llmFactory.create(modelName);
    }

    /**
     * 异常分类：LlmCallException 直接透传（Provider 已按状态码标注 nonRetryable）；
     * 其他异常（连接失败、超时等非 HTTP 错误）视为可重试。
     */
    private LlmCallException classifyException(Exception e) {
        if (e instanceof LlmCallException) {
            return (LlmCallException) e;
        }
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return new LlmCallException(msg, false);
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmCallException("Retry sleep interrupted", false);
        }
    }
}
