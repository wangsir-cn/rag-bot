package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * AI 模型配置（按用户绑定）。modelType: CHAT / EMBEDDING。
 * 生成模型与向量模型完全独立，各自 base-url / api-key / model-name。
 */
public record AiModelConfig(
        UUID id,
        UUID userId,
        String name,
        String modelType,
        String baseUrl,
        String apiKey,
        String modelName,
        Integer dimensions,
        boolean isDefault,
        OffsetDateTime createdAt
) {
    public boolean isChat() { return "CHAT".equalsIgnoreCase(modelType); }
    public boolean isEmbedding() { return "EMBEDDING".equalsIgnoreCase(modelType); }
}
