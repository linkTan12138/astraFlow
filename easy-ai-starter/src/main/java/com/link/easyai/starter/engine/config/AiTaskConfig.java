package com.link.easyai.starter.engine.config;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Represents a complete business scenario configuration.
 * <p>
 * Example task types: ORDER_UPDATE, ORDER_CREATE, ISSUE_HANDLE, ORDER_CANCEL.
 * <p>
 * Each conversation turn uses the current published task metadata and extraction overrides for its tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTaskConfig {

    /** Task type identifier, e.g. "ORDER_UPDATE" */
    private String taskType;

    /** Human-readable name */
    private String name;

    /** Description of this task scenario */
    private String description;

    /** Intent keywords from @AiTask.triggers or the current config override. */
    private List<String> keywords;

    /** Example expressions from @AiTask.triggers or the current config override. */
    private List<String> examples;

    /** Ordered list of field definitions */
    private List<FieldDefinition> fields;

    /** Completion criteria configuration */
    private CompletionConfig completion;

    /** Task executor to execute when all fields are collected (or directly for pure-action tasks) */
    private TaskExecuteConfig executeConfig;

    /** Extension points for future use */
    private Map<String, Object> extensions;

    /**
     * Find a field definition by code.
     * @return the FieldDefinition, or null if not found
     */
    public FieldDefinition getField(String fieldCode) {
        if (fields == null) return null;
        return fields.stream()
                .filter(f -> f.getCode().equals(fieldCode))
                .findFirst()
                .orElse(null);
    }
}
