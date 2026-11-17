package com.example.ragbot.domain;

/** 检索命中的 chunk 及其融合分数与两路原始排名（未命中则为 -1） */
public record RetrievedChunk(
        ChunkRecordV2 chunk,
        double score,
        int vectorRank,
        int bm25Rank
) {}
