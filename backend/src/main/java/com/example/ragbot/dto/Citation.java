package com.example.ragbot.dto;

/** 回答中的一个引用来源，index 对应 Prompt 中的 [n] 编号 */
public record Citation(
        int index,
        String chunkId,
        String documentId,
        String documentName,
        int chunkIndex,
        double score,
        String contentPreview,
        String chunkType,
        String answer,
        String knowledgeBaseId
) {
    /** 兼容旧构造（无 chunkType 等） */
    public Citation(int index, String chunkId, String documentId, String documentName,
                    int chunkIndex, double score, String contentPreview) {
        this(index, chunkId, documentId, documentName, chunkIndex, score, contentPreview,
                "TEXT", null, null);
    }
}
