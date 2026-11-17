package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 切片（v2）：chunkType TEXT/QA。
 * TEXT: content=正文, answer=null
 * QA  : content=问题, answer=答案
 */
public record ChunkRecordV2(
        UUID id,
        UUID documentId,
        UUID knowledgeBaseId,
        int chunkIndex,
        String chunkType,
        String content,
        String answer,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {}
