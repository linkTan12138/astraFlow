package com.link.easyai.starter.engine.llm;

/**
 * Exception thrown when an LLM call fails.
 * <p>
 * Carries a {@code nonRetryable} flag so the caller knows whether to retry
 * or fail fast. Non-retryable errors are client-side issues (bad prompt,
 * auth failure, invalid request) where retrying won't help.
 */
public class LlmCallException extends RuntimeException {

    private final boolean nonRetryable;

    /** HTTP 状态码；0 表示非 HTTP 错误（网络异常、超时、解析失败等）。 */
    private final int statusCode;

    public LlmCallException(String message, boolean nonRetryable) {
        this(message, null, nonRetryable, 0);
    }

    public LlmCallException(String message, boolean nonRetryable, int statusCode) {
        this(message, null, nonRetryable, statusCode);
    }

    public LlmCallException(String message, Throwable cause, boolean nonRetryable) {
        this(message, cause, nonRetryable, 0);
    }

    public LlmCallException(String message, Throwable cause, boolean nonRetryable, int statusCode) {
        super(message, cause);
        this.nonRetryable = nonRetryable;
        this.statusCode = statusCode;
    }

    public boolean isNonRetryable() {
        return nonRetryable;
    }

    public boolean isRetryable() {
        return !nonRetryable;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
