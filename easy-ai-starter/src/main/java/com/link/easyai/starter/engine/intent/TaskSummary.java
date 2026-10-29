package com.link.easyai.starter.engine.intent;

import lombok.Builder;
import lombok.Data;

/**
 * 进行中任务摘要（多任务意图路由专用）。
 * <p>
 * 一个会话可并存多个进行中任务，意图路由时需要把每个任务的
 * 关键信息（taskId、类型、业务实体标识、已收集字段、最近对话）摘要化喂给 LLM，
 * 让 LLM 判断当前用户消息归属哪个任务。
 */
@Data
@Builder
public class TaskSummary {

    /** 任务 ID（路由归属性，continue_existing / cancel_existing 时返回它） */
    private String taskId;

    /** 任务类型（taskType，如 CREATE_TICKET） */
    private String taskType;

    /** 任务名称（用户可见，如 创建工单） */
    private String taskName;

    /** 任务描述（可选） */
    private String taskDescription;

    /** 待收集字段摘要（如 "联系电话、优先级"，无则显示"无"） */
    private String pendingFieldsSummary;

    /**
     * 已声明为路由标识的已收集字段，例如 "客户单号=TEST001"。
     * 用于将带有同一业务实体标识的消息关联到正确任务。
     */
    private String entityReferencesSummary;

    /** 已收集字段和值摘要（敏感字段只标记为已收集，不暴露值）。 */
    private String collectedFieldsSummary;

    /** 最近一条用户消息（可选，用于区分消息归属）。 */
    private String lastUserMessage;

    /** 最近一条 AI 回复（可选，用于区分消息归属） */
    private String lastAiReply;
}
