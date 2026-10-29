package com.link.easyai.starter.llm;

import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionChoice;
import com.volcengine.ark.runtime.model.completion.chat.ChatCompletionRequest;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessage;
import com.volcengine.ark.runtime.model.completion.chat.ChatMessageRole;
import com.volcengine.ark.runtime.service.ArkService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 豆包（Doubao / 火山引擎方舟）内置 Provider。
 * 使用 Volcengine Ark SDK 调用。
 */
public class DoubaoProvider implements LLMProvider {

    private static final Logger log = LoggerFactory.getLogger(DoubaoProvider.class);

    private static final String DEFAULT_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3";

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final Duration timeout;

    /**
     * 复用的 ArkService 单例。ArkService 内部持有 OkHttpClient 连接池与线程池，
     * 每次调用重建会导致连接无法复用、线程反复创建销毁，改为懒加载单例。
     */
    private volatile ArkService arkService;

    public DoubaoProvider(LLMConfig.ProviderConfig config) {
        this.apiKey = config.getApiKey();
        this.endpoint = config.getEndpoint() != null ? config.getEndpoint() : DEFAULT_ENDPOINT;
        this.model = config.getModel();
        int timeoutSeconds = config.getTimeoutSeconds() != null && config.getTimeoutSeconds() > 0
                ? config.getTimeoutSeconds() : 60;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public String chat(List<Message> messages, LLMConfig config) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("doubao 需要配置 model（接入点 ID 或模型 ID）");
        }

        ArkService arkService = getOrCreateService();

        try {
            List<ChatMessage> chatMessages = new ArrayList<>(messages.size());
            for (Message msg : messages) {
                ChatMessageRole role = convertRole(msg.getRole());
                chatMessages.add(ChatMessage.builder()
                        .role(role)
                        .content(msg.getContent())
                        .build());
            }

            ChatCompletionRequest request = ChatCompletionRequest.builder()
                    .model(model)
                    .messages(chatMessages)
                    .build();

            List<ChatCompletionChoice> choices = arkService.createChatCompletion(request).getChoices();
            if (choices != null && !choices.isEmpty()) {
                return choices.get(0).getMessage().getContent().toString();
            }
            return "";
        } catch (Exception e) {
            log.error("[DoubaoProvider] call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Doubao 接口异常: " + e.getMessage(), e);
        }
    }

    /**
     * 懒加载单例 ArkService（双重检查锁，线程安全）。
     * 复用连接池与线程池，避免高频调用下的资源抖动。
     */
    private ArkService getOrCreateService() {
        ArkService local = arkService;
        if (local == null) {
            synchronized (this) {
                local = arkService;
                if (local == null) {
                    local = ArkService.builder()
                            .apiKey(apiKey)
                            .baseUrl(endpoint)
                            .connectTimeout(timeout)
                            .callTimeout(timeout)
                            .build();
                    arkService = local;
                    log.info("[DoubaoProvider] ArkService initialized (endpoint={}, timeout={}s)",
                            endpoint, timeout.getSeconds());
                }
            }
        }
        return local;
    }

    @Override
    public Stream<String> streamChat(List<Message> messages, LLMConfig config) {
        throw new UnsupportedOperationException("Doubao 流式输出待实现");
    }

    @Override
    public String getName() {
        return "doubao";
    }

    private ChatMessageRole convertRole(String role) {
        if (role == null) {
            return ChatMessageRole.USER;
        }
        return switch (role.toLowerCase()) {
            case "system" -> ChatMessageRole.SYSTEM;
            case "assistant" -> ChatMessageRole.ASSISTANT;
            default -> ChatMessageRole.USER;
        };
    }
}
