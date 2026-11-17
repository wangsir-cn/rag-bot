package com.example.ragbot.dto;

import java.util.UUID;

public record UploadResponse(
        UUID documentId,
        String originalFilename,
        long fileSize,
        String status,
        String message
) {}
