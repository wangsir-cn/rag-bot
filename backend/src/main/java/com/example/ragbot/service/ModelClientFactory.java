package com.example.ragbot.service;

import com.example.ragbot.domain.AiModelConfig;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时按用户模型配置构建 Spring AI 客户端并缓存。
 * 每个用户可绑定不同服务商的 Chat / Embedding 模型（url、apiKey、model 完全独立）。
 * 缓存 key 用配置内容指纹，模型配置变更后自动失效并重建。
 */
@Service
public class ModelClientFactory {

    private final Map<String, OpenAiEmbeddingModel> embeddingCache = new ConcurrentHashMap<>();
    private final Map<String, ChatClient> chatCache = new ConcurrentHashMap<>();

    /**
     * 单次回复的最大输出 token 数。
     * <=0 表示不设置该参数，输出上限完全由模型/服务端决定。
     * 注意：推理模型的"思考 token"也计入这个上限；GLM-5.3 写长文时
     * reasoning 可能消耗上万 token，上限过低会导致正文为空（finish_reason=length）。
     */
    @Value("${spring.ai.openai.chat.options.max-tokens:32768}")
    private Integer maxTokens;

    public OpenAiEmbeddingModel embeddingModel(AiModelConfig cfg) {
        if (!cfg.isEmbedding())
            throw new IllegalArgumentException("模型类型不是 EMBEDDING: " + cfg.modelName());
        String key = fingerprint(cfg);
        return embeddingCache.computeIfAbsent(key, k -> {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(stripTrailingSlash(cfg.baseUrl()))
                    .apiKey(cfg.apiKey())
                    .build();
            var options = OpenAiEmbeddingOptions.builder()
                    .model(cfg.modelName())
                    .dimensions(cfg.dimensions())
                    .build();
            return new OpenAiEmbeddingModel(api,
                    org.springframework.ai.document.MetadataMode.NONE, options);
        });
    }

    public ChatClient chatClient(AiModelConfig cfg) {
        if (!cfg.isChat())
            throw new IllegalArgumentException("模型类型不是 CHAT: " + cfg.modelName());
        String key = fingerprint(cfg);
        return chatCache.computeIfAbsent(key, k -> {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(stripTrailingSlash(cfg.baseUrl()))
                    .apiKey(cfg.apiKey())
                    .build();
            var optionsBuilder = OpenAiChatOptions.builder()
                    .model(cfg.modelName())
                    .temperature(0.2);
            // maxTokens <= 0 时不设置，交给模型自身的上限决定
            if (maxTokens != null && maxTokens > 0) {
                optionsBuilder.maxTokens(maxTokens);
            }
            var options = optionsBuilder.build();
            OpenAiChatModel chatModel = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(options)
                    .build();
            return ChatClient.create(chatModel);
        });
    }

    /** base-url 不能带 /v1 结尾，否则 Spring AI 会拼成 /v1/v1/embeddings */
    private String stripTrailingSlash(String url) {
        String s = url.trim();
        while (s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
        return s;
    }

    private String fingerprint(AiModelConfig cfg) {
        return cfg.modelType() + "|" + cfg.baseUrl() + "|" + cfg.apiKey() + "|"
                + cfg.modelName() + "|" + cfg.dimensions();
    }

    /** 配置变更后清理缓存（按配置指纹自动失效，此方法用于全量清） */
    public void clearCache() {
        embeddingCache.clear();
        chatCache.clear();
    }
}
