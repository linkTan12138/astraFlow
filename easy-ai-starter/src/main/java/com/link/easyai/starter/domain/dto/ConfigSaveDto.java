package com.link.easyai.starter.domain.dto;

import com.link.easyai.starter.engine.config.ExtractionConfig;
import com.link.easyai.starter.engine.config.TaskMetadataOverrides;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Request DTO for saving a field-extraction override config draft.
 * <p>
 * 数据库配置支持任务 name/description/triggers 和字段提取规则的覆盖，
 * 任务结构与执行逻辑一律来自注解。
 * <p>
 * 示例：
 * <pre>
 * {
 *   "taskType": "CREATE_TICKET",
 *   "tenantId": null,          // null 或省略 = 全局模板；非空 = 租户私有覆盖
 *   "task": { "name": "创建客服工单", "triggers": ["创建工单"] },
 *   "fields": {
 *     "phone":       { "description": "...", "examples": ["13800138000"], "rules": ["必须是11位数字"] },
 *     "description": { "rules": ["如包含投诉意图需提取投诉对象和事由"] }
 *   }
 * }
 * </pre>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfigSaveDto {

    /** Task type，必须由 @AiTask 注解声明 */
    private String taskType;

    /** 租户 ID；null 或空白表示全局默认模板 */
    private String tenantId;

    /** 可选任务元信息覆盖，有效值覆盖注解，未填或空白则沿用注解。 */
    private TaskMetadataOverrides task;

    /** 字段提取覆盖：fieldCode → extraction 覆盖（非空字段才覆盖） */
    private Map<String, ExtractionConfig> fields;
}
