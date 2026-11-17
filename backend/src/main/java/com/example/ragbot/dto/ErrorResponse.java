package com.example.ragbot.dto;

import java.time.Instant;

public record ErrorResponse(String error, String path, Instant timestamp) {}
