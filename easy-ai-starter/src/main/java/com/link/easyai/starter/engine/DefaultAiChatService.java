package com.link.easyai.starter.engine;

import com.link.easyai.starter.domain.entity.AiChatSession;
import com.link.easyai.starter.engine.context.TaskContext;
import com.link.easyai.starter.engine.intent.IntentEngine;
import com.link.easyai.starter.engine.intent.IntentResult;
import com.link.easyai.starter.engine.intent.TaskSummary;
import com.link.easyai.starter.engine.lock.TaskLockManager;
import com.link.easyai.starter.engine.history.ChatHistoryManager;
import com.link.easyai.starter.engine.history.ChatMessage;
import com.link.easyai.starter.engine.config.AiTaskConfig;
import com.link.easyai.starter.engine.config.FieldDefinition;
import com.link.easyai.starter.engine.observability.PromptInjectionDetector;
import com.link.easyai.starter.engine.session.SessionManager;
import com.link.easyai.starter.engine.state.TaskState;
import com.link.easyai.starter.engine.state.TaskStateManager;
import com.link.easyai.starter.engine.state.TaskStatus;
import com.link.easyai.starter.engine.state.FieldState;
import com.link.easyai.starter.engine.task.DefaultTaskExecutor;
import com.link.easyai.starter.engine.task.TaskResult;
import com.link.easyai.starter.engine.util.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 默认 AI 聊天服务实现。
 * <p>
 * 统一入口，自动处理：
 * <ol>
 *   <li>会话状态管理（加载/创建/超时判断）</li>
 *   <li>意图识别（LLM 优先，带上下文判断 continue/switch/cancel）</li>
 *   <li>任务切换（旧任务取消，新任务创建）</li>
 *   <li>任务执行（委托给 AiTaskEngine）</li>
 *   <li>输入安全（长度限制 + Prompt 注入检测）</li>
 * </ol>
 */
@Service
public class DefaultAiChatService implements AiChatService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAiChatService.class);

    private final AiTaskEngine aiTaskEngine;
    private final IntentEngine intentEngine;
    private final SessionManager sessionManager;
    private final TaskStateManager taskStateManager;
    private final AiTaskProperties properties;
    private final SnowflakeIdGenerator idGenerator;
    private final TaskLockManager lockManager;
    private final ChatHistoryManager chatHistoryManager;
    private final AiTaskConfigService configService;
    private final ObjectProvider<DefaultTaskExecutor> defaultTaskExecutorProvider;

    @Autowired
    public DefaultAiChatService(AiTaskEngine aiTaskEngine,
                                 IntentEngine intentEngine,
                                 SessionManager sessionManager,
                                 TaskStateManager taskStateManager,
                                 AiTaskProperties properties,
                                 SnowflakeIdGenerator idGenerator,
                                 TaskLockManager lockManager,
                                 ChatHistoryManager chatHistoryManager,
                                 AiTaskConfigService configService,
                                 ObjectProvider<DefaultTaskExecutor> defaultTaskExecutorProvider) {
        this.aiTaskEngine = aiTaskEngine;
        this.intentEngine = intentEngine;
        this.sessionManager = sessionManager;
        this.taskStateManager = taskStateManager;
        this.properties = properties;
        this.idGenerator = idGenerator;
        this.lockManager = lockManager;
        this.chatHistoryManager = chatHistoryManager;
        this.configService = configService;
        this.defaultTaskExecutorProvider = defaultTaskExecutorProvider;
    }

    @Override
    public ChatResponse chat(String message, String sessionId, TaskContext context) {
        if (message == null || message.isBlank()) {
            return ChatResponse.fallback("请输入有效消息");
        }

        // 1. 输入安全检查
        String safeMessage = sanitizeInput(message);
        if (safeMessage == null) {
            // 注入检测命中且配置为阻断
            return ChatResponse.fallback("您的消息包含可疑指令，已被拦截。请正常描述您的需求。");
        }

        // 2. 分布式锁：防止同一 (tenantId, sessionId) 并发请求导致状态错乱
        //    锁过期时间与最坏 LLM 链耗时联动（重试 + fallback 场景下单次请求可能远超 60 秒）
        String lockTenant = context != null && context.getTenantId() != null && !context.getTenantId().isBlank()
                ? context.getTenantId() : "0";
        String lockKey = "easyai:session:" + lockTenant + ":" + sessionId;
        String lockToken = lockManager.tryLock(lockKey, computeLockExpireSeconds());
        if (lockToken == null) {
            log.warn("[AiChatService] session={} tenant={} is locked by another request, rejecting",
                    sessionId, lockTenant);
            return ChatResponse.fallback("请求过于频繁，请稍后再试");
        }

        try {
            return doChat(safeMessage, sessionId, context);
        } finally {
            lockManager.unlock(lockKey, lockToken);
        }
    }

    private ChatResponse doChat(String safeMessage, String sessionId, TaskContext context) {
        // 3. 加载/创建会话（(tenant_id, session_id) 复合键定位）
        String tenantId = context != null && context.getTenantId() != null && !context.getTenantId().isBlank()
                ? context.getTenantId() : "0";
        AiChatSession session = sessionManager.loadOrCreate(sessionId, tenantId);
        // 以 session 记录的租户为权威（同一 sessionId 在不同租户下是独立会话）
        String sessionTenant = session.getTenantId() != null ? session.getTenantId() : tenantId;

        // 3. 会话超时检查：超时后自动重置并把当前消息作为新会话的第一条消息处理
        int timeoutMinutes = properties.getLifecycle() != null
                ? properties.getLifecycle().getTimeoutMinutes() : 30;
        if (sessionManager.isExpired(session, timeoutMinutes)) {
            // 多任务：把该会话下所有进行中任务批量标记为 EXPIRED
            expireAllActiveTasks(sessionId, sessionTenant);
            // 重置会话为 IDLE，复用当前 sessionId，避免用户必须换 ID 才能继续
            sessionManager.reset(sessionId, sessionTenant);
            // 超时重置时清空对话历史，开启全新会话
            chatHistoryManager.clearHistory(sessionId, sessionTenant);
            log.info("[AiChatService] session={} tenant={} expired ({}min idle), reset and continuing as new session",
                    sessionId, sessionTenant, timeoutMinutes);
            // 同步清理内存对象，避免后续判断误用旧值
            session.setCurrentTaskId(null);
            session.setCurrentTaskType(null);
            session.setStatus(AiChatSession.STATUS_IDLE);
        }

        // 4. 多任务路由：列出该会话所有进行中任务（最近活跃在前，最多 MAX_ACTIVE_TASKS 个）
        List<TaskState> activeTasks = taskStateManager.listActiveBySession(sessionId, sessionTenant, MAX_ACTIVE_TASKS);

        ChatResponse response;
        if (activeTasks.isEmpty()) {
            // 无进行中任务：全新意图识别
            response = handleNewTask(safeMessage, session, context);
        } else {
            // 有进行中任务：多任务意图路由（continue_existing / switch_new / cancel_existing）
            response = handleWithActiveTasks(safeMessage, session, activeTasks, context);
        }

        // 记录对话历史（滑动窗口），用于下一轮的上下文理解
        try {
            String taskId = response.getTaskId() != null ? response.getTaskId() : session.getCurrentTaskId();
            String taskType = response.getTaskType() != null ? response.getTaskType() : session.getCurrentTaskType();
            chatHistoryManager.appendUserMessage(sessionId, safeMessage, taskId, taskType, sessionTenant);
            chatHistoryManager.appendAssistantMessage(sessionId, response.getMessage(), taskId, taskType, sessionTenant);
        } catch (Exception e) {
            log.warn("[AiChatService] failed to record chat history for session={}: {}", sessionId, e.getMessage());
        }

        return response;
    }

    @Override
    public ChatResponse chatWithTaskType(String message, String sessionId, String taskType, TaskContext context) {
        if (message == null || message.isBlank() || taskType == null || taskType.isBlank()) {
            return ChatResponse.fallback("message 和 taskType 不能为空");
        }

        String safeMessage = sanitizeInput(message);
        if (safeMessage == null) {
            // 注入检测命中且配置为阻断
            return ChatResponse.fallback("您的消息包含可疑指令，已被拦截。请正常描述您的需求。");
        }

        // 分布式锁：防止同一 (tenantId, sessionId) 并发请求
        String lockTenant = context != null && context.getTenantId() != null && !context.getTenantId().isBlank()
                ? context.getTenantId() : "0";
        String lockKey = "easyai:session:" + lockTenant + ":" + sessionId;
        String lockToken = lockManager.tryLock(lockKey, computeLockExpireSeconds());
        if (lockToken == null) {
            log.warn("[AiChatService] chatWithTaskType session={} tenant={} is locked, rejecting",
                    sessionId, lockTenant);
            return ChatResponse.fallback("请求过于频繁，请稍后再试");
        }

        try {
            return doChatWithTaskType(safeMessage, sessionId, taskType, context);
        } finally {
            lockManager.unlock(lockKey, lockToken);
        }
    }

    private ChatResponse doChatWithTaskType(String safeMessage, String sessionId, String taskType, TaskContext context) {
        String tenantId = context != null && context.getTenantId() != null && !context.getTenantId().isBlank()
                ? context.getTenantId() : "0";
        AiChatSession session = sessionManager.loadOrCreate(sessionId, tenantId);
        String sessionTenant = session.getTenantId() != null ? session.getTenantId() : tenantId;

        // 会话超时检查：与 chat() 保持一致，超时后自动重置再创建新任务
        int timeoutMinutes = properties.getLifecycle() != null
                ? properties.getLifecycle().getTimeoutMinutes() : 30;
        if (sessionManager.isExpired(session, timeoutMinutes)) {
            // 多任务：把该会话下所有进行中任务批量标记为 EXPIRED
            expireAllActiveTasks(sessionId, sessionTenant);
            sessionManager.reset(sessionId, sessionTenant);
            chatHistoryManager.clearHistory(sessionId, sessionTenant);
            log.info("[AiChatService] chatWithTaskType session={} tenant={} expired, reset and continuing",
                    sessionId, sessionTenant);
            session.setCurrentTaskId(null);
            session.setCurrentTaskType(null);
            session.setStatus(AiChatSession.STATUS_IDLE);
        }

        // 直接创建/绑定任务（指定 taskType，无意图识别，传 null）
        ChatResponse response = createAndExecuteTask(safeMessage, session, taskType, context, null);

        // 记录对话历史
        try {
            String taskId = response.getTaskId() != null ? response.getTaskId() : session.getCurrentTaskId();
            String respTaskType = response.getTaskType() != null ? response.getTaskType() : taskType;
            chatHistoryManager.appendUserMessage(sessionId, safeMessage, taskId, respTaskType, sessionTenant);
            chatHistoryManager.appendAssistantMessage(sessionId, response.getMessage(), taskId, respTaskType, sessionTenant);
        } catch (Exception e) {
            log.warn("[AiChatService] failed to record chat history (chatWithTaskType) session={}: {}", sessionId, e.getMessage());
        }

        return response;
    }

    // ---- 多任务路由：判断 continue_existing / switch_new / cancel_existing ----

    /**
     * 进行中任务数量上限：同一会话最多并行保留 5 个进行中任务，
     * 路由时只向 LLM 列出最近活跃的 5 个；超出部分在会话超时后统一过期。
     */
    private static final int MAX_ACTIVE_TASKS = 5;

    private ChatResponse handleWithActiveTasks(String message, AiChatSession session,
                                                List<TaskState> activeTasks, TaskContext context) {
        String sessionId = session.getSessionId();
        String tenantId = session.getTenantId() != null ? session.getTenantId() : "0";

        // 本轮候选和任务摘要共用当前租户的一份生效配置。
        Map<String, AiTaskConfig> allConfigs = configService.getPublishedConfigs(tenantId);
        // 1. 构建每个进行中任务的摘要（taskId/type/名称/待收集字段/最近AI回复）
        List<TaskSummary> summaries = new ArrayList<>(activeTasks.size());
        for (TaskState task : activeTasks) {
            summaries.add(buildTaskSummary(sessionId, tenantId, task, allConfigs.get(task.getTaskType())));
        }

        // 2. LLM 多任务路由
        IntentResult result = intentEngine.recognizeWithTasks(message, summaries, allConfigs);

        // 3. cancel_existing：取消目标任务，其余任务保留
        if (result.isCancelExisting()) {
            String targetTaskId = result.getTargetTaskId();
            cancelTask(targetTaskId);
            log.info("[AiChatService] session={} tenant={} cancelled task={}, {} active tasks remain",
                    sessionId, tenantId, targetTaskId, activeTasks.size() - 1);
            return ChatResponse.fallback("好的，已取消该任务。还有其他进行中的任务需要继续吗？")
                    .withIntent(result.getReason(), result.getConfidence(), sourceName(result));
        }

        // 4. switch_new：开启全新任务，旧任务不取消（多任务并存）
        if (result.isSwitchNew() && result.getTaskType() != null) {
            log.info("[AiChatService] session={} tenant={} starting new task {} alongside {} active tasks",
                    sessionId, tenantId, result.getTaskType(), activeTasks.size());
            return createAndExecuteTask(message, session, result.getTaskType(), context, result)
                    .withIntent(result.getReason(), result.getConfidence(), sourceName(result));
        }

        // 5. continue_existing（含默认归属最近活跃任务）：继续目标任务
        String targetTaskId = result.getTargetTaskId();
        TaskState targetTask = null;
        for (TaskState task : activeTasks) {
            if (targetTaskId != null && targetTaskId.equals(task.getTaskId())) {
                targetTask = task;
                break;
            }
        }
        if (targetTask == null) {
            // 防呆：targetTaskId 无效时归属最近活跃的任务（列表第一个）
            targetTask = activeTasks.get(0);
        }
        return executeExistingTask(message, session, targetTask.getTaskId(), targetTask.getTaskType(), context)
                .withIntent(result.getReason(), result.getConfidence(), sourceName(result));
    }

    /**
     * 构建单个进行中任务的摘要，供多任务意图路由使用。
     */
    private TaskSummary buildTaskSummary(String sessionId, String tenantId, TaskState task, AiTaskConfig taskConfig) {
        String taskType = task.getTaskType();
        String taskName = taskType;
        String taskDescription = null;

        if (taskConfig != null) {
            taskName = taskConfig.getName() != null ? taskConfig.getName() : taskType;
            taskDescription = taskConfig.getDescription();
        }

        // 待收集字段摘要：从任务配置的字段列表中减去已收集的
        String pendingFieldsSummary = "无";
        if (taskConfig != null && taskConfig.getFields() != null && !taskConfig.getFields().isEmpty()) {
            List<String> pending = new ArrayList<>();
            for (FieldDefinition field : taskConfig.getFields()) {
                if (field != null && field.getCode() != null) {
                    boolean collected = task.isFieldCompleted(field.getCode());
                    if (!collected) {
                        String label = field.getName() != null ? field.getName() : field.getCode();
                        pending.add(label);
                    }
                }
            }
            pendingFieldsSummary = pending.isEmpty() ? "无（所有字段已收集）" : String.join("、", pending);
        }

        String entityReferencesSummary = buildCollectedFieldsSummary(task, taskConfig, true);
        String collectedFieldsSummary = buildCollectedFieldsSummary(task, taskConfig, false);

        // 最近一轮用户和 AI 消息（按任务隔离的历史，取各自最后一条）
        String lastAiReply = null;
        String lastUserMessage = null;
        try {
            List<ChatMessage> history = chatHistoryManager.loadHistoryByTask(sessionId, task.getTaskId(), tenantId);
            if (history != null) {
                for (int i = history.size() - 1; i >= 0; i--) {
                    ChatMessage msg = history.get(i);
                    if (msg == null || msg.getRole() == null) {
                        continue;
                    }
                    if (lastAiReply == null && "assistant".equalsIgnoreCase(msg.getRole())) {
                        lastAiReply = msg.getContent();
                    } else if (lastUserMessage == null && "user".equalsIgnoreCase(msg.getRole())) {
                        lastUserMessage = msg.getContent();
                    }
                    if (lastAiReply != null && lastUserMessage != null) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[AiChatService] failed to load task history for task={}: {}", task.getTaskId(), e.getMessage());
        }

        return TaskSummary.builder()
                .taskId(task.getTaskId())
                .taskType(taskType)
                .taskName(taskName)
                .taskDescription(taskDescription)
                .pendingFieldsSummary(pendingFieldsSummary)
                .entityReferencesSummary(entityReferencesSummary)
                .collectedFieldsSummary(collectedFieldsSummary)
                .lastUserMessage(truncateForRouting(lastUserMessage))
                .lastAiReply(truncateForRouting(lastAiReply))
                .build();
    }

    /**
     * 为多任务路由生成已收集字段摘要。显式路由标识单独输出，普通字段仍保留，
     * 以便没有可声明标识的业务由 LLM 根据字段语义和对话上下文判断归属。
     */
    private String buildCollectedFieldsSummary(TaskState task,
                                               AiTaskConfig config,
                                               boolean routingIdentitiesOnly) {
        if (task.getFields() == null || task.getFields().isEmpty()
                || config == null || config.getFields() == null) {
            return "无";
        }
        StringJoiner summary = new StringJoiner("；");
        for (FieldDefinition definition : config.getFields()) {
            if (definition == null || definition.getCode() == null
                    || (routingIdentitiesOnly && !definition.isRoutingIdentity())) {
                continue;
            }
            FieldState state = task.getField(definition.getCode());
            if (state == null || !state.isCompleted() || state.getValue() == null) {
                continue;
            }
            String name = definition.getName() != null ? definition.getName() : definition.getCode();
            String value = definition.isSensitive()
                    ? "<敏感信息，已收集>"
                    : truncateForRouting(state.getDisplayValue() != null && !state.getDisplayValue().isBlank()
                            ? state.getDisplayValue() : String.valueOf(state.getValue()));
            summary.add(definition.getCode() + "（" + name + "）=" + value);
        }
        String result = summary.toString();
        return result.isBlank() ? "无" : result;
    }

    private String truncateForRouting(String value) {
        if (value == null || value.isBlank()) {
            return value;
        }
        int maxLength = 500;
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    /**
     * 将会话下所有进行中任务批量标记为 EXPIRED（会话超时重置时调用）。
     */
    private void expireAllActiveTasks(String sessionId, String tenantId) {
        try {
            List<TaskState> active = taskStateManager.listActiveBySession(sessionId, tenantId, 100);
            int expired = 0;
            for (TaskState task : active) {
                try {
                    task.setStatus(TaskStatus.EXPIRED);
                    taskStateManager.save(task);
                    expired++;
                } catch (Exception e) {
                    log.warn("[AiChatService] failed to expire task={}: {}", task.getTaskId(), e.getMessage());
                }
            }
            log.info("[AiChatService] expired {} active tasks for session={} tenant={}", expired, sessionId, tenantId);
        } catch (Exception e) {
            log.warn("[AiChatService] failed to expire active tasks for session={}: {}", sessionId, e.getMessage());
        }
    }

    // ---- 无活跃任务：全新意图识别 ----

    private ChatResponse handleNewTask(String message, AiChatSession session, TaskContext context) {
        IntentResult result = intentEngine.recognize(message, session.getTenantId());

        if (result.isNoMatch()) {
            // 无匹配：优先流转到客户自定义的默认任务（若已实现）
            DefaultTaskExecutor defaultTask = defaultTaskExecutorProvider.getIfAvailable();
            if (defaultTask != null) {
                try {
                    DefaultTaskContext defaultCtx = DefaultTaskContext.builder()
                            .message(message)
                            .sessionId(session.getSessionId())
                            .tenantId(session.getTenantId() != null ? session.getTenantId() : "0")
                            .taskContext(context)
                            .build();
                    TaskResult defaultResult = defaultTask.execute(defaultCtx);
                    String reply = (defaultResult != null && defaultResult.getMessage() != null)
                            ? defaultResult.getMessage() : "抱歉，我不太确定您想做什么。";
                    log.info("[AiChatService] session={} intent=NoMatch routed to DefaultTask, reply={}",
                            session.getSessionId(), reply);
                    return ChatResponse.fallback(reply)
                            .withTaskType("DEFAULT")
                            .withIntent(result.getReason(), result.getConfidence(), sourceName(result));
                } catch (Exception e) {
                    log.warn("[AiChatService] DefaultTask execution failed, fall back to built-in hint: {}", e.getMessage());
                }
            }

            // 客户未实现默认任务 / 默认任务执行失败：返回兜底 + 候选列表
            List<String> candidates = result.getCandidates();
            String hint = "抱歉，我不太确定您想做什么。";
            if (candidates != null && !candidates.isEmpty()) {
                hint += " 您可以尝试：" + String.join("、", candidates);
            }
            return ChatResponse.fallback(hint)
                    .withIntent(result.getReason(), result.getConfidence(), sourceName(result));
        }

        if (result.isAmbiguous()) {
            // 低置信度，返回澄清
            return ChatResponse.clarify(
                    "您是想进行以下哪个操作？",
                    result.getCandidates(),
                    result.getReason())
                    .withIntent(result.getReason(), result.getConfidence(), sourceName(result));
        }

        // 高置信度，创建任务
        return createAndExecuteTask(message, session, result.getTaskType(), context, result)
                .withIntent(result.getReason(), result.getConfidence(), sourceName(result));
    }

    // ---- 创建并执行任务 ----

    private ChatResponse createAndExecuteTask(String message, AiChatSession session,
                                                String taskType, TaskContext context,
                                                IntentResult intentResult) {
        // 创建任务（通过 AiTaskEngine 的初始化机制）
        // 注意：AiTaskEngine.execute 内部会处理任务状态的创建/加载
        String taskId = generateTaskId(session.getSessionId(), taskType);

        // 绑定任务到会话（按租户+会话复合键）
        sessionManager.bindTask(session.getSessionId(), taskId, taskType,
                session.getTenantId() != null ? session.getTenantId() : "0");

        // 将意图识别信息放入 TaskContext，供引擎在任务创建时持久化
        if (intentResult != null) {
            if (context == null) {
                context = TaskContext.builder().data(new LinkedHashMap<>()).build();
            }
            context.setIntentReason(intentResult.getReason());
            context.setIntentConfidence(intentResult.getConfidence());
            context.setIntentSource(intentResult.getSource() != null ? intentResult.getSource().name() : null);
        }

        // 执行任务
        return executeExistingTask(message, session, taskId, taskType, context);
    }

    // ---- 执行已有任务 ----

    private ChatResponse executeExistingTask(String message, AiChatSession session,
                                               String taskId, String taskType, TaskContext context) {
        // 更新会话活跃时间
        sessionManager.touch(session.getSessionId(),
                session.getTenantId() != null ? session.getTenantId() : "0");

        // 构建 TaskContext
        TaskContext taskContext = context != null ? context : TaskContext.builder()
                .taskId(taskId)
                .taskType(taskType)
                .tenantId(session.getTenantId())
                .data(new LinkedHashMap<>())
                .build();
        // 配置查询以当前会话记录的租户为准，补齐已有请求上下文中的租户信息。
        taskContext.setTenantId(session.getTenantId());
        if (taskContext.getTaskId() == null) {
            taskContext.setTaskId(taskId);
        }
        if (taskContext.getTaskType() == null) {
            taskContext.setTaskType(taskType);
        }
        // 设置 sessionId，用于引擎加载对话历史
        if (taskContext.getSessionId() == null) {
            taskContext.setSessionId(session.getSessionId());
        }

        // 执行任务管道
        AiTaskResponse engineResponse = aiTaskEngine.execute(taskType, taskId, message, taskContext);

        // 任务完成时清除会话绑定
        if (engineResponse.isCompleted()) {
            sessionManager.clearTask(session.getSessionId(),
                    session.getTenantId() != null ? session.getTenantId() : "0");
            log.info("[AiChatService] session={} task={} completed, session cleared",
                    session.getSessionId(), taskId);
        }

        return mapToChatResponse(engineResponse, taskType);
    }

    // ---- 辅助方法 ----

    private String sanitizeInput(String message) {
        // 长度限制
        int maxLength = properties.getLlm() != null && properties.getLlm().getMaxInputLength() > 0
                ? properties.getLlm().getMaxInputLength() : 2000;
        if (message.length() > maxLength) {
            log.warn("[AiChatService] input truncated from {} to {} chars", message.length(), maxLength);
            message = message.substring(0, maxLength);
        }

        // Prompt 注入检测：受 easy-ai.task-engine.llm.injection-detection-enabled 开关控制；
        // injection-block-enabled=true 时命中即拦截（返回 null），否则仅记录告警
        AiTaskProperties.Llm llmCfg = properties.getLlm();
        if (llmCfg != null && llmCfg.isInjectionDetectionEnabled()
                && PromptInjectionDetector.isInjectionDetected(message)) {
            if (llmCfg.isInjectionBlockEnabled()) {
                log.warn("[AiChatService] prompt injection detected, message blocked");
                return null;
            }
            log.warn("[AiChatService] potential prompt injection detected in user message");
        }

        return message;
    }

    private void cancelTask(String taskId) {
        try {
            TaskState state = taskStateManager.load(taskId, null);
            if (state != null && state.getTaskId() != null) {
                state.setStatus(TaskStatus.CANCELLED);
                taskStateManager.save(state);
                log.info("[AiChatService] task={} marked CANCELLED", taskId);
            }
        } catch (Exception e) {
            log.warn("[AiChatService] failed to cancel task={}: {}", taskId, e.getMessage());
        }
    }

    /**
     * 计算会话锁过期时间（秒）：与最坏 LLM 链耗时联动。
     * <p>
     * 最坏单次请求耗时 = fallback 链长 × (maxRetries + 1) × 单次调用超时。
     * 一次对话可能触发多段 LLM 调用（意图识别 / continue 判断 / 参数提取等），
     * 因此再乘 2 倍余量并加 60 秒缓冲，避免长调用下锁提前过期导致互斥失效。
     */
    private int computeLockExpireSeconds() {
        AiTaskProperties.Llm cfg = properties.getLlm();
        int timeout = cfg != null && cfg.getTimeoutSeconds() > 0 ? cfg.getTimeoutSeconds() : 60;
        int retries = cfg != null && cfg.getMaxRetries() > 0 ? cfg.getMaxRetries() : 3;
        int chainLength = 1 + (cfg != null && cfg.getFallbackModels() != null
                ? cfg.getFallbackModels().length : 0);
        long worstCaseSeconds = (long) chainLength * (retries + 1) * timeout;
        long expire = worstCaseSeconds * 2 + 60;
        return (int) Math.min(expire, Integer.MAX_VALUE);
    }

    private String generateTaskId(String sessionId, String taskType) {
        // 雪花算法生成全局唯一、趋势递增的任务ID
        // 替代原有的"时间戳+随机数"方案，消除高并发下的ID碰撞风险
        return idGenerator.nextIdString();
    }

    /**
     * 将 IntentResult 的匹配来源转为字符串（用于响应中调试展示）。
     */
    private String sourceName(IntentResult result) {
        return result != null && result.getSource() != null ? result.getSource().name() : null;
    }

    private ChatResponse mapToChatResponse(AiTaskResponse engineResponse, String taskType) {
        if (engineResponse.isCompleted()) {
            return ChatResponse.done(
                    engineResponse.getTaskId(),
                    engineResponse.getMessage(),
                    engineResponse.getTaskResult(),
                    engineResponse.getState()
            ).withTaskType(taskType);
        }

        return ChatResponse.needMore(
                engineResponse.getTaskId(),
                engineResponse.getMessage(),
                engineResponse.getState()
        ).withTaskType(taskType);
    }
}
