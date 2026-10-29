package com.link.easyai.starter.engine;

import com.link.easyai.starter.domain.entity.AiTaskConfigRecord;
import com.link.easyai.starter.engine.config.FieldExtractionOverrides;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** 任务配置覆盖存储。租户优先，无已发布记录时查询全局（NULL 或空字符串）配置。 */
public interface ExtractionOverrideStore {
    /** 两个作用域均无配置时返回 null，使用注解默认值。 */
    FieldExtractionOverrides getPublishedOverrides(String taskType, String tenantId);

    /** 批量读取注册任务的当前覆盖；数据库实现使用一次查询。 */
    default Map<String, FieldExtractionOverrides> getPublishedOverridesByTaskTypes(Collection<String> taskTypes, String tenantId) {
        Map<String, FieldExtractionOverrides> result = new LinkedHashMap<>();
        for (String taskType : taskTypes) {
            FieldExtractionOverrides overrides = getPublishedOverrides(taskType, tenantId);
            if (overrides != null) result.put(taskType, overrides);
        }
        return result;
    }
    /** 保存当前作用域的唯一草稿，存在则更新。 */
    AiTaskConfigRecord saveDraft(String taskType, String tenantId, FieldExtractionOverrides overrides);
    /** 按记录 ID 发布草稿，事务内禁用同作用域的旧发布；管理操作不使用全局兜底。 */
    AiTaskConfigRecord publish(Long id, String tenantId);

    /** 在发布事务和草稿行锁内完成任务注册、字段等业务校验，失败则不改变发布状态。 */
    AiTaskConfigRecord publish(Long id, String tenantId, BiConsumer<String, FieldExtractionOverrides> validator);
    /** 按记录 ID 禁用当前作用域的已发布配置。 */
    AiTaskConfigRecord disable(Long id, String tenantId);
    /** 列出当前作用域的记录，按更新时间、ID 倒序。 */
    List<AiTaskConfigRecord> list(String taskType, String tenantId);
}
