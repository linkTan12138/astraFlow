package com.link.easyai.starter.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.easyai.starter.domain.entity.AiTaskConfigRecord;
import com.link.easyai.starter.engine.config.FieldExtractionOverrides;
import com.link.easyai.starter.engine.exception.ConfigNotFoundException;
import com.link.easyai.starter.engine.exception.ConfigValidationException;
import com.link.easyai.starter.mapper.AiTaskConfigRecordMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** 按租户、任务类型和状态存取任务元信息及字段提取覆盖。NULL 或空字符串租户均为全局作用域。 */
@Component
public class DefaultAiTaskConfigService implements ExtractionOverrideStore {

    private final AiTaskConfigRecordMapper configMapper;
    private final ObjectMapper objectMapper;

    public DefaultAiTaskConfigService(AiTaskConfigRecordMapper configMapper, ObjectMapper objectMapper) {
        this.configMapper = configMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public FieldExtractionOverrides getPublishedOverrides(String taskType, String tenantId) {
        requireTaskType(taskType);
        String tenant = normalizeTenant(tenantId);
        AiTaskConfigRecord record = findByStatus(taskType, tenant, AiTaskConfigRecord.STATUS_PUBLISHED);
        if (record == null && tenant != null) {
            record = findByStatus(taskType, null, AiTaskConfigRecord.STATUS_PUBLISHED);
        }
        return record == null ? null : parse(record);
    }

    @Override
    public Map<String, FieldExtractionOverrides> getPublishedOverridesByTaskTypes(Collection<String> taskTypes, String tenantId) {
        if (taskTypes == null || taskTypes.isEmpty()) return Map.of();
        String tenant = normalizeTenant(tenantId);
        LambdaQueryWrapper<AiTaskConfigRecord> query = new LambdaQueryWrapper<AiTaskConfigRecord>()
                .in(AiTaskConfigRecord::getTaskType, taskTypes)
                .eq(AiTaskConfigRecord::getStatus, AiTaskConfigRecord.STATUS_PUBLISHED);
        query.and(scope -> {
            if (tenant != null) scope.eq(AiTaskConfigRecord::getTenantId, tenant).or();
            scope.isNull(AiTaskConfigRecord::getTenantId).or().eq(AiTaskConfigRecord::getTenantId, "");
        });
        Map<String, AiTaskConfigRecord> globals = new LinkedHashMap<>();
        Map<String, AiTaskConfigRecord> tenants = new LinkedHashMap<>();
        for (AiTaskConfigRecord record : configMapper.selectList(query)) {
            Map<String, AiTaskConfigRecord> scope = record.getTenantId() == null || record.getTenantId().isEmpty()
                    ? globals : tenants;
            if (scope.putIfAbsent(record.getTaskType(), record) != null) {
                throw new ConfigValidationException("存在重复的已发布配置: taskType=" + record.getTaskType());
            }
        }
        // 先按记录选择作用域，再解析；未被选中的全局记录不参与租户覆盖。
        globals.putAll(tenants);
        Map<String, FieldExtractionOverrides> result = new LinkedHashMap<>();
        globals.forEach((type, record) -> result.put(type, parse(record)));
        return result;
    }

    private AiTaskConfigRecord findByStatus(String taskType, String tenantId, String status) {
        LambdaQueryWrapper<AiTaskConfigRecord> query = new LambdaQueryWrapper<AiTaskConfigRecord>()
                .eq(AiTaskConfigRecord::getTaskType, taskType)
                .eq(AiTaskConfigRecord::getStatus, status);
        applyTenant(query, tenantId);
        // 不用 LIMIT 隐藏重复有效配置；唯一约束保证每个作用域仅有一条。
        return configMapper.selectOne(query);
    }

    private void applyTenant(LambdaQueryWrapper<AiTaskConfigRecord> query, String tenantId) {
        if (tenantId == null) {
            query.and(scope -> scope.isNull(AiTaskConfigRecord::getTenantId)
                    .or().eq(AiTaskConfigRecord::getTenantId, ""));
        } else {
            query.eq(AiTaskConfigRecord::getTenantId, tenantId);
        }
    }

    private FieldExtractionOverrides parse(AiTaskConfigRecord record) {
        if (record.getConfigJson() == null || record.getConfigJson().isBlank()) {
            throw new ConfigValidationException("配置 JSON 为空: id=" + record.getId());
        }
        try {
            FieldExtractionOverrides overrides = objectMapper.readValue(
                    record.getConfigJson(), FieldExtractionOverrides.class);
            if (overrides == null) {
                throw new ConfigValidationException("配置 JSON 不能为 null: id=" + record.getId());
            }
            if (overrides.getTaskType() != null && !overrides.getTaskType().isBlank()
                    && !record.getTaskType().equals(overrides.getTaskType())) {
                throw new ConfigValidationException("配置 taskType 与表行不一致: id=" + record.getId());
            }
            overrides.setTaskType(record.getTaskType());
            return overrides;
        } catch (ConfigValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new ConfigValidationException("配置 JSON 解析失败: id=" + record.getId(), e);
        }
    }

    @Override
    @Transactional
    public AiTaskConfigRecord saveDraft(String taskType, String tenantId, FieldExtractionOverrides overrides) {
        requireTaskType(taskType);
        String tenant = normalizeTenant(tenantId);
        AiTaskConfigRecord record = findByStatus(taskType, tenant, AiTaskConfigRecord.STATUS_DRAFT);
        FieldExtractionOverrides value = overrides == null ? new FieldExtractionOverrides() : overrides;
        value.setTaskType(taskType);
        String json;
        try {
            json = objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ConfigValidationException("配置 JSON 序列化失败: taskType=" + taskType, e);
        }
        if (record != null) {
            record.setConfigJson(json);
            // 状态条件防止并发发布后仍把新内容写入已发布记录。
            int rows = configMapper.update(record, new LambdaUpdateWrapper<AiTaskConfigRecord>()
                    .eq(AiTaskConfigRecord::getId, record.getId())
                    .eq(AiTaskConfigRecord::getStatus, AiTaskConfigRecord.STATUS_DRAFT));
            if (rows == 0) {
                throw new ConfigValidationException("草稿已变更，请重新保存");
            }
            return record;
        }
        record = new AiTaskConfigRecord();
        record.setTaskType(taskType);
        record.setTenantId(tenant);
        record.setConfigJson(json);
        record.setStatus(AiTaskConfigRecord.STATUS_DRAFT);
        record.setDeleted(0);
        configMapper.insert(record);
        return record;
    }

    @Override
    @Transactional
    public AiTaskConfigRecord publish(Long id, String tenantId) {
        return publish(id, tenantId, (taskType, overrides) -> { });
    }

    @Override
    @Transactional
    public AiTaskConfigRecord publish(Long id, String tenantId, BiConsumer<String, FieldExtractionOverrides> validator) {
        AiTaskConfigRecord record = requireRecord(id, normalizeTenant(tenantId));
        if (!AiTaskConfigRecord.STATUS_DRAFT.equals(record.getStatus())) {
            throw new ConfigValidationException("只能发布 DRAFT 配置: id=" + id);
        }
        validator.accept(record.getTaskType(), parse(record));
        LambdaUpdateWrapper<AiTaskConfigRecord> update = new LambdaUpdateWrapper<AiTaskConfigRecord>()
                .eq(AiTaskConfigRecord::getTaskType, record.getTaskType())
                .eq(AiTaskConfigRecord::getStatus, AiTaskConfigRecord.STATUS_PUBLISHED)
                .set(AiTaskConfigRecord::getStatus, AiTaskConfigRecord.STATUS_DISABLED);
        String tenant = normalizeTenant(record.getTenantId());
        if (tenant == null) {
            update.and(scope -> scope.isNull(AiTaskConfigRecord::getTenantId)
                    .or().eq(AiTaskConfigRecord::getTenantId, ""));
        } else {
            update.eq(AiTaskConfigRecord::getTenantId, tenant);
        }
        configMapper.update(null, update);
        record.setStatus(AiTaskConfigRecord.STATUS_PUBLISHED);
        record.setPublishedTime(new Date());
        configMapper.updateById(record);
        return record;
    }

    @Override
    @Transactional
    public AiTaskConfigRecord disable(Long id, String tenantId) {
        AiTaskConfigRecord record = requireRecord(id, normalizeTenant(tenantId));
        if (!AiTaskConfigRecord.STATUS_PUBLISHED.equals(record.getStatus())) {
            throw new ConfigValidationException("只能禁用 PUBLISHED 配置: id=" + id);
        }
        record.setStatus(AiTaskConfigRecord.STATUS_DISABLED);
        configMapper.updateById(record);
        return record;
    }

    private AiTaskConfigRecord requireRecord(Long id, String tenantId) {
        if (id == null) {
            throw new ConfigValidationException("配置 id 不能为空");
        }
        LambdaQueryWrapper<AiTaskConfigRecord> query = new LambdaQueryWrapper<AiTaskConfigRecord>()
                .eq(AiTaskConfigRecord::getId, id);
        applyTenant(query, tenantId);
        query.last("FOR UPDATE");
        AiTaskConfigRecord record = configMapper.selectOne(query);
        if (record == null) {
            throw new ConfigNotFoundException("id=" + id);
        }
        return record;
    }

    @Override
    public List<AiTaskConfigRecord> list(String taskType, String tenantId) {
        LambdaQueryWrapper<AiTaskConfigRecord> query = new LambdaQueryWrapper<>();
        if (taskType != null && !taskType.isBlank()) {
            query.eq(AiTaskConfigRecord::getTaskType, taskType);
        }
        applyTenant(query, normalizeTenant(tenantId));
        query.orderByDesc(AiTaskConfigRecord::getUpdateTime).orderByDesc(AiTaskConfigRecord::getId);
        return configMapper.selectList(query);
    }

    private void requireTaskType(String taskType) {
        if (taskType == null || taskType.isBlank()) {
            throw new ConfigNotFoundException(taskType);
        }
    }

    private String normalizeTenant(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? null : tenantId.trim();
    }
}
