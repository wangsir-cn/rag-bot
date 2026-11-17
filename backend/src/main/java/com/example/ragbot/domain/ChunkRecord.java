package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** chunks 表的一行（不含向量，避免把高维数组拉进业务对象） */
public record ChunkRecord(
        UUID id,
        UUID documentId,
        int chunkIndex,
        String content,
        Map<String, Object> metadata,
        OffsetDateTime createdAt
) {}
