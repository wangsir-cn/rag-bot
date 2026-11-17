package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 文档：归属知识库。fileKind: TEXT / QA；status 沿用 DocumentStatus */
public record DocumentRecord(
        UUID id,
        UUID knowledgeBaseId,
        UUID userId,
        String filename,
        String originalFilename,
        String filePath,
        long fileSize,
        String mimeType,
        String fileKind,
        DocumentStatus status,
        String errorMessage,
        int chunkCount,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {}
