package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 问答会话：用户维度，绑定知识库 */
public record ChatSession(
        UUID id,
        UUID userId,
        UUID knowledgeBaseId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
