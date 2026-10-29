package com.link.easyai.starter.engine.task;

import com.link.easyai.starter.engine.DefaultTaskContext;

/**
 * 默认（兜底）任务执行器。
 * <p>
 * 当用户消息无法匹配到任何已注册的 {@link AiTask} 场景（意图识别 NoMatch）时：
 * <ul>
 *   <li>若 Spring 容器中存在本接口的实现 Bean，则把消息流转到该实现处理；</li>
 *   <li>若不存在任何实现，则回退到框架内置提示（"抱歉，我不太确定您想做什么..."）。</li>
 * </ul>
 * <p>
 * 客户只需在自己的工程中提供一个实现即可启用，适用于：闲聊应答、转人工、
 * 企业专属兜底话术等场景。本接口不参与意图识别与任务注册，纯兜底。
 * <p>
 * 示例：
 * <pre>
 * &#64;Component
 * public class MyDefaultTask implements DefaultTaskExecutor {
 *     &#64;Override
 *     public TaskResult execute(DefaultTaskContext context) {
 *         return TaskResult.success("我是兜底助手，您说的是：" + context.getMessage(), null);
 *     }
 * }
 * </pre>
 */
public interface DefaultTaskExecutor {

    /**
     * 处理无法匹配到任何场景的用户消息。
     *
     * @param context 兜底任务上下文（含原始消息、sessionId、tenantId、请求上下文）
     * @return 执行结果（message 会作为回复返回给用户）
     */
    TaskResult execute(DefaultTaskContext context);
}
