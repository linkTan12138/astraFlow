package com.link.easyai.starter.engine;

import com.link.easyai.starter.domain.entity.AiTaskConfigRecord;
import com.link.easyai.starter.engine.config.AiTaskConfig;
import com.link.easyai.starter.engine.config.FieldExtractionOverrides;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** 注解定义任务结构，数据库提供任务元信息和字段提取覆盖。 */
public interface AiTaskConfigService {
    /** 每轮读取当前发布：租户优先，无记录则查全局，均不存在则使用注解默认值。 */
    AiTaskConfig getLatestPublished(String taskType, String tenantId);

    /** 当前租户全部注册任务的生效配置，用于意图识别、多任务路由和功能介绍。 */
    default Map<String, AiTaskConfig> getPublishedConfigs(String tenantId) {
        Map<String, AiTaskConfig> result = new LinkedHashMap<>();
        for (String taskType : getAnnotationConfigs().keySet()) {
            result.put(taskType, getLatestPublished(taskType, tenantId));
        }
        return result;
    }
    /** 原始注解默认配置（不含覆盖），供任务类型枚举和诊断使用。 */
    default Map<String, AiTaskConfig> getAnnotationConfigs() {
        return java.util.Collections.emptyMap();
    }
    /** 保存当前作用域的唯一草稿。 */
    AiTaskConfigRecord saveDraft(String taskType, String tenantId, FieldExtractionOverrides overrides);
    /** 按 ID 发布草稿，禁用同作用域的旧发布。 */
    AiTaskConfigRecord publish(Long id, String tenantId);
    /** 按 ID 禁用当前作用域的已发布配置。 */
    AiTaskConfigRecord disable(Long id, String tenantId);
    /** 按更新时间、ID 倒序列出当前作用域的记录。 */
    List<AiTaskConfigRecord> list(String taskType, String tenantId);
}
