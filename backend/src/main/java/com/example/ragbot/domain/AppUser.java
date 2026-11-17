package com.example.ragbot.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 用户实体。role: ADMIN / USER */
public record AppUser(
        UUID id,
        String username,
        String email,
        String passwordHash,
        String role,
        boolean mustChangePassword,
        boolean enabled,
        OffsetDateTime createdAt
) {
    public boolean isAdmin() { return "ADMIN".equals(role); }
}
