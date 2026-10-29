package com.link.easyai.starter.engine;

import com.link.easyai.starter.engine.context.TaskContext;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 默认（兜底）任务上下文。
 * <p>
 * 当用户消息未匹配到任何已注册场景（意图识别 NoMatch）时，
 * 框架将消息封装为该上下文，并传递给
 * {@link com.link.easyai.starter.engine.task.DefaultTaskExecutor} 处理。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultTaskContext {

    /** 用户原始消息（已做安全处理：长度限制 + 注入检测） */
    private String message;

    /** 会话 ID */
    private String sessionId;

    /** 租户 ID */
    private String tenantId;

    /** 原始请求级上下文（含 userDetails、data 等，可能为 null） */
    private TaskContext taskContext;
}
