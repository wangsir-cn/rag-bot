package com.example.ragbot.dto;

import java.util.List;
import java.util.Map;

public record RetrievalDebugResponse(
        List<ChunkHit> hits,
        int vectorCount,
        int bm25Count,
        String fusion,
        long vectorMs,
        long bm25Ms,
        long totalMs,
        Map<String, Object> applied
) {
    public record ChunkHit(
            String chunkId,
            String documentId,
            String documentName,
            int chunkIndex,
            double score,
            String content,
            Map<String, Object> metadata,
            int vectorRank,
            int bm25Rank
    ) {}
}
