package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 知识库：用户维度，必须绑定一个 Embedding 模型 */
public record KnowledgeBase(
        UUID id,
        UUID userId,
        String name,
        String description,
        UUID embeddingModelId,
        String chunkStrategy,
        int chunkSize,
        int chunkOverlap,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
