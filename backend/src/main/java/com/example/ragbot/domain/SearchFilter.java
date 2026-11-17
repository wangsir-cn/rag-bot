package com.example.ragbot.domain;

import java.time.OffsetDateTime;

public record SearchFilter(String docType, OffsetDateTime dateFrom, OffsetDateTime dateTo) {}
