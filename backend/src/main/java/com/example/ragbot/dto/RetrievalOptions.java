package com.example.ragbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 检索调试用的可选参数覆盖。
 * 所有字段均可为 null：null 表示沿用 application.yml 中的全局默认配置。
 * 仅用于 /api/chat/retrieve 调试接口，不影响问答 /stream 链路。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RetrievalOptions(
        Integer topK,                // 最终返回条数
        Integer candidateK,          // 每路召回的候选数量
        Double similarityThreshold,  // 向量相似度阈值
        String fusion,               // RRF / WEIGHTED
        Integer rrfK,                // RRF 平滑常数 k
        Double vectorWeight,         // 加权融合时向量权重
        Double bm25Weight,           // 加权融合时 BM25 权重
        Boolean rerankEnabled        // 本次是否启用 rerank
) {
    public static RetrievalOptions defaults() {
        return new RetrievalOptions(null, null, null, null, null, null, null, null);
    }
}
