package com.link.easyai.starter.engine.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Objects;

/** @AiTask 元信息覆盖；无有效值的属性继续使用注解默认值。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskMetadataOverrides {
    private String name;
    private String description;
    private List<String> triggers;

    /** 空白项不参与覆盖；有有效触发词时整体替换注解列表。 */
    public List<String> normalizedTriggers() {
        return triggers == null ? List.of() : triggers.stream()
                .filter(Objects::nonNull)
                .map(String::strip)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }
}
