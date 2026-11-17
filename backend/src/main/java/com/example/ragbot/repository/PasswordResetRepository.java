package com.example.ragbot.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public class PasswordResetRepository {

    private final JdbcTemplate jdbc;
    public PasswordResetRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void create(String rawToken, UUID userId, int ttlMinutes) {
        jdbc.update("""
                INSERT INTO password_reset_tokens (token, user_id, expires_at) VALUES (?, ?, now() + (? * interval '1 minute'))
                """, hash(rawToken), userId, ttlMinutes);
    }

    public java.util.Optional<UUID> findValidUser(String rawToken) {
        return jdbc.query("""
                SELECT user_id FROM password_reset_tokens
                WHERE token = ? AND used = FALSE AND expires_at > now()
                """, (rs, n) -> UUID.fromString(rs.getString("user_id")), hash(rawToken))
                .stream().findFirst();
    }

    public void markUsed(String rawToken) {
        jdbc.update("UPDATE password_reset_tokens SET used = TRUE WHERE token = ?", hash(rawToken));
    }

    public void deleteExpired() {
        jdbc.update("DELETE FROM password_reset_tokens WHERE expires_at < now() - interval '1 day'");
    }

    private String hash(String raw) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
