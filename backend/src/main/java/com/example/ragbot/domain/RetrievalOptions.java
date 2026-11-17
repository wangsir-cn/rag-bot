package com.example.ragbot.domain;

import java.time.OffsetDateTime;

public record RetrievalOptions(
        int topK,
        int candidateK,
        double similarityThreshold,
        String fusion,
        String docType,
        OffsetDateTime dateFrom,
        OffsetDateTime dateTo
) {}
