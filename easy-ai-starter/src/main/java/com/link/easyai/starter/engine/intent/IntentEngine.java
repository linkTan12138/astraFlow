package com.link.easyai.starter.engine.intent;

import com.link.easyai.starter.engine.config.AiTaskConfig;

import java.util.List;
import java.util.Map;

/**
 * 意图识别引擎。
 * <p>
 * 三种识别模式：
 * <ol>
 *   <li><b>全新对话识别</b> — 无活跃任务时，LLM 分类用户意图，匹配到任务类型</li>
 *   <li><b>带上下文识别</b> — 有单个活跃任务时，LLM 同时判断是继续当前任务、切换到新任务、还是取消</li>
 *   <li><b>多任务路由</b> — 有多个进行中任务时，LLM 判断消息归属哪个任务（continue_existing）、
 *       开启新任务（switch_new）、还是取消某个任务（cancel_existing）</li>
 * </ol>
 * LLM 优先，关键词匹配仅作为 LLM 失败时的降级手段。
 */
public interface IntentEngine {

    /**
     * 全新对话意图识别（无活跃任务）。
     *
     * @param userMessage 用户消息
     * @return 意图识别结果
     */
    IntentResult recognize(String userMessage);

    /** 按当前对话租户使用已发布任务配置；旧实现可继续实现单参数入口。 */
    default IntentResult recognize(String userMessage, String tenantId) {
        return recognize(userMessage);
    }

    /**
     * 带当前任务上下文的意图识别（有活跃任务时调用）。
     * <p>
     * LLM 会同时判断：
     * <ul>
     *   <li>continue - 继续当前任务</li>
     *   <li>switch - 切换到新任务（result.taskType 为新任务类型）</li>
     *   <li>cancel - 取消当前任务</li>
     * </ul>
     *
     * @param userMessage           用户消息
     * @param currentTaskType       当前活跃任务类型
     * @param currentTaskName       当前活跃任务名称
     * @param currentTaskDescription 当前活跃任务描述
     * @param collectedFields       已收集字段的简要描述
     * @param lastAiReply           上一轮 AI 回复（可能为 null）
     * @param recentHistory         最近对话历史（可能为 null 或空）
     * @return 意图识别结果（含 action 字段）
     */
    IntentResult recognizeWithContext(String userMessage,
                                        String currentTaskType,
                                        String currentTaskName,
                                        String currentTaskDescription,
                                        String collectedFields,
                                        String lastAiReply,
                                        String recentHistory);

    /** 带租户的上下文识别，当前任务和候选任务均使用该租户的生效配置。 */
    default IntentResult recognizeWithContext(String userMessage, String currentTaskType,
                                              String currentTaskName, String currentTaskDescription,
                                              String collectedFields, String lastAiReply,
                                              String recentHistory, String tenantId) {
        return recognizeWithContext(userMessage, currentTaskType, currentTaskName,
                currentTaskDescription, collectedFields, lastAiReply, recentHistory);
    }

    /**
     * 多任务意图路由：有多个进行中任务时，判断用户消息归属哪个任务。
     * <p>
     * LLM 根据每个进行中任务的摘要（taskId、类型、待收集字段、最近AI回复）
     * 判断：
     * <ul>
     *   <li>continue_existing - 归属某个进行中任务（result.targetTaskId 为目标任务）</li>
     *   <li>switch_new - 开启全新任务（result.taskType 为新任务类型，不取消旧任务）</li>
     *   <li>cancel_existing - 取消某个进行中任务（result.targetTaskId 为目标任务，其余保留）</li>
     * </ul>
     * 拿不准时默认归属最近活跃的任务（列表第一个）。
     *
     * @param userMessage 用户消息
     * @param activeTasks 进行中任务摘要列表（最近活跃在前，由调用方保证顺序）
     * @param configs     全部任务配置（用于 switch_new 候选语义匹配及新任务类型校验）
     * @return 意图路由结果（含 action / targetTaskId）
     */
    IntentResult recognizeWithTasks(String userMessage,
                                    List<TaskSummary> activeTasks,
                                    Map<String, AiTaskConfig> configs);

    /**
     * 获取所有可用任务类型（用于澄清 UI）。
     */
    List<String> listAllTaskTypes();

    /**
     * 判断用户当前消息是否与上一轮未完成任务具有连续性。
     * <p>
     * 当 session 中没有活跃任务时调用此方法，通过 LLM 判断用户消息是在
     * 继续上一轮任务（补充参数、修正信息等），还是开启一个全新的话题。
     * <p>
     * 典型连续场景：上一轮提示"请提供关税支付方式"，用户回复"寄付"。
     * 典型不连续场景：上一轮在下单，用户说"帮我看看有什么功能"。
     *
     * @param userMessage       当前用户消息
     * @param lastTaskType      上一轮任务类型
     * @param lastTaskName      上一轮任务名称
     * @param lastCollectedFields 上一轮已收集字段的简要描述
     * @param lastAiReply       上一轮 AI 回复（可能为 null），通常包含"还需要什么信息"的提示
     * @return true 表示连续，应恢复并继续上一轮任务；false 表示应开启新任务
     */
    boolean judgeContinuity(String userMessage,
                             String lastTaskType,
                             String lastTaskName,
                             String lastCollectedFields,
                             String lastAiReply);
}
