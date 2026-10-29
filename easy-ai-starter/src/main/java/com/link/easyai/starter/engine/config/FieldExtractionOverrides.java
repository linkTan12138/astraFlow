package com.link.easyai.starter.engine.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 任务元信息和字段提取覆盖集，对应 {@code ai_task_config.config_json}。
 * 保留历史类名以兼容已有字段配置调用方。
 * <p>
 * 框架以注解（@AiTask / @AiTaskParam / @AiExtract）定义任务结构与默认提取规则，
 * 数据库配置支持任务 name/description/triggers 以及字段提取规则的热更新覆盖。
 * <p>
 * config_json 结构示例：
 * <pre>
 * {
 *   "taskType": "CREATE_TICKET",
 *   "task": { "name": "创建客服工单", "description": "用户需要创建工单时使用", "triggers": ["创建工单"] },
 *   "fields": {
 *     "phone":       { "description": "...", "examples": ["13800138000"], "rules": ["必须是11位数字"] },
 *     "description": { "rules": ["如包含投诉意图需提取投诉对象和事由"] }
 *   }
 * }
 * </pre>
 * 解析时校验 {@code taskType} 与表行一致；合并时按 {@code fieldCode} 精确匹配注解字段，
 * 引用不存在的字段会在发布/加载时报清晰错误，避免静默失效。
 */
@JsonIgnoreProperties(value = "version")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldExtractionOverrides {

    /** 冗余任务类型，用于与表行 {@code task_type} 一致性校验（表列为权威） */
    private String taskType;

    /** 任务名称、描述、触发词；可省略，兼容原有仅 fields 的配置。 */
    private TaskMetadataOverrides task;

    /** 字段提取覆盖：fieldCode → extraction 覆盖（非空字段才覆盖） */
    private Map<String, ExtractionConfig> fields;
}
