package com.link.easyai.starter.engine.intent;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

/**
 * 意图识别结果。
 * <p>
 * 包含匹配的任务类型、置信度、判断理由、候选任务，以及
 * 当前对话的动作判断（继续/切换/取消）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IntentResult {

    /** 高置信度阈值 */
    public static final double HIGH_CONFIDENCE_THRESHOLD = 0.75;

    /** 对话动作：继续当前任务 */
    public static final String ACTION_CONTINUE = "continue";
    /** 对话动作：切换到新任务 */
    public static final String ACTION_SWITCH = "switch";
    /** 对话动作：取消当前任务 */
    public static final String ACTION_CANCEL = "cancel";

    /** 多任务动作：继续某个进行中任务（targetTaskId 为目标任务） */
    public static final String ACTION_CONTINUE_EXISTING = "continue_existing";
    /** 多任务动作：开启全新任务（taskType 为新任务类型，不取消旧任务） */
    public static final String ACTION_SWITCH_NEW = "switch_new";
    /** 多任务动作：取消某个进行中任务（targetTaskId 为目标任务，其余任务保留） */
    public static final String ACTION_CANCEL_EXISTING = "cancel_existing";

    /** 匹配的任务类型，null 表示无匹配 */
    private String taskType;

    /** 置信度 0.0-1.0 */
    private double confidence;

    /** 判断理由（用于 trace/debug） */
    private String reason;

    /** 低置信度时的候选任务列表 */
    private List<String> candidates;

    /** 匹配来源 */
    private MatchSource source;

    /**
     * 对话动作判断（仅在有活跃任务时有意义）：
     * continue - 继续当前任务
     * switch - 切换到新任务（taskType 为新任务类型）
     * cancel - 取消当前任务
     * 多任务模式下使用 continue_existing / switch_new / cancel_existing。
     */
    private String action;

    /**
     * 多任务路由的目标任务 ID：
     * continue_existing / cancel_existing 时指向某个进行中任务。
     */
    private String targetTaskId;

    /**
     * 是否高置信度（可直接执行）。
     */
    public boolean isHighConfidence() {
        return confidence >= HIGH_CONFIDENCE_THRESHOLD && taskType != null;
    }

    /**
     * 是否模糊（低置信度且多候选）。
     */
    public boolean isAmbiguous() {
        return confidence < HIGH_CONFIDENCE_THRESHOLD
                && candidates != null && candidates.size() > 1;
    }

    /**
     * 是否无匹配。
     */
    public boolean isNoMatch() {
        return taskType == null;
    }

    /**
     * 是否是切换动作。
     */
    public boolean isSwitch() {
        return ACTION_SWITCH.equals(action);
    }

    /**
     * 是否是取消动作。
     */
    public boolean isCancel() {
        return ACTION_CANCEL.equals(action);
    }

    /**
     * 是否是继续动作。
     */
    public boolean isContinue() {
        return ACTION_CONTINUE.equals(action) || action == null;
    }

    /**
     * 多任务：是否归属某个进行中任务（continue_existing）。
     */
    public boolean isContinueExisting() {
        return ACTION_CONTINUE_EXISTING.equals(action);
    }

    /**
     * 多任务：是否开启全新任务（switch_new）。
     */
    public boolean isSwitchNew() {
        return ACTION_SWITCH_NEW.equals(action);
    }

    /**
     * 多任务：是否取消某个进行中任务（cancel_existing）。
     */
    public boolean isCancelExisting() {
        return ACTION_CANCEL_EXISTING.equals(action);
    }

    // ---- 工厂方法 ----

    public static IntentResult keywordMatch(String taskType, String reason) {
        return IntentResult.builder()
                .taskType(taskType)
                .confidence(0.95)
                .reason(reason)
                .source(MatchSource.KEYWORD)
                .build();
    }

    public static IntentResult fallback(String reason) {
        return IntentResult.builder()
                .taskType(null)
                .confidence(0.0)
                .reason(reason)
                .source(MatchSource.FALLBACK)
                .build();
    }

    public static IntentResult continueTask(String taskType) {
        return IntentResult.builder()
                .taskType(taskType)
                .confidence(1.0)
                .reason("Continuing existing task")
                .source(MatchSource.CONTINUE)
                .action(ACTION_CONTINUE)
                .build();
    }

    public static IntentResult cancel() {
        return IntentResult.builder()
                .action(ACTION_CANCEL)
                .confidence(1.0)
                .reason("User cancelled the task")
                .source(MatchSource.LLM)
                .build();
    }
}
