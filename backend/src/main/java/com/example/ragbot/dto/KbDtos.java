package com.example.ragbot.dto;

import com.example.ragbot.domain.KnowledgeBase;

import java.util.Map;

public class KbDtos {
    public record KbRequest(String name, String description, java.util.UUID embeddingModelId,
                            String chunkStrategy, Integer chunkSize, Integer chunkOverlap) {}

    public record KbVO(java.util.UUID id, String name, String description,
                       java.util.UUID embeddingModelId, String embeddingModelName,
                       String chunkStrategy, int chunkSize, int chunkOverlap,
                       int documentCount, int chunkCount, String createdAt) {
        public static KbVO from(KnowledgeBase kb, String modelName, int docCount, int chunkCount, String createdAt) {
            return new KbVO(kb.id(), kb.name(), kb.description(), kb.embeddingModelId(), modelName,
                    kb.chunkStrategy(), kb.chunkSize(), kb.chunkOverlap(), docCount, chunkCount, createdAt);
        }
    }

    public record ChunkVO(java.util.UUID id, java.util.UUID documentId, int chunkIndex,
                          String chunkType, String content, String answer,
                          Map<String, Object> metadata, String createdAt) {}
}
