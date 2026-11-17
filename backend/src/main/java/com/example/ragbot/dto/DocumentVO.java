package com.example.ragbot.dto;

import com.example.ragbot.domain.Document;
import com.example.ragbot.domain.DocumentStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DocumentVO(
        UUID id,
        String originalFilename,
        String docType,
        DocumentStatus status,
        long fileSize,
        int chunkCount,
        String errorMessage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static DocumentVO from(Document d) {
        return new DocumentVO(d.id(), d.originalFilename(), d.docType(), d.status(),
                d.fileSize(), d.chunkCount(), d.errorMessage(), d.createdAt(), d.updatedAt());
    }
}
