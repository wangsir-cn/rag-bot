package com.example.ragbot.domain;

import java.util.Map;
import java.util.UUID;

/** 阶段 2 的输出对象 */
public record Chunk(
        UUID documentId,
        int chunkIndex,
        String content,
        Map<String, Object> metadata
) {}
