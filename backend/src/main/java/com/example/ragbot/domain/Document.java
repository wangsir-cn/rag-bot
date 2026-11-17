package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record Document(
        UUID id,
        String filename,
        String originalFilename,
        String filePath,
        long fileSize,
        String mimeType,
        String docType,
        DocumentStatus status,
        String errorMessage,
        int chunkCount,
        Map<String, Object> metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
