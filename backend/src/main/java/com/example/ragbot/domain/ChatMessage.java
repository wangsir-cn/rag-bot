package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** 会话消息。role: USER / ASSISTANT；citations 为 JSON 序列化的引用数组 */
public record ChatMessage(
        UUID id,
        UUID sessionId,
        String role,
        String content,
        String citations,
        OffsetDateTime createdAt
) {}
