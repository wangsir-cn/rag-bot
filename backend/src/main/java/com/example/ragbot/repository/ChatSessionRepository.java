package com.example.ragbot.repository;

import com.example.ragbot.domain.ChatMessage;
import com.example.ragbot.domain.ChatSession;
import com.example.ragbot.util.JsonbUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ChatSessionRepository {

    private final JdbcTemplate jdbc;
    public ChatSessionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<ChatSession> MAPPER = (rs, n) -> new ChatSession(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getObject("knowledge_base_id", UUID.class),
            rs.getString("title"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class)
    );

    private static final RowMapper<ChatMessage> MSG_MAPPER = (rs, n) -> new ChatMessage(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("session_id")),
            rs.getString("role"),
            rs.getString("content"),
            rs.getString("citations"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
    );

    public List<ChatSession> findByUser(UUID userId) {
        return jdbc.query("SELECT * FROM chat_sessions WHERE user_id = ? ORDER BY updated_at DESC", MAPPER, userId);
    }

    public Optional<ChatSession> findByIdAndUser(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM chat_sessions WHERE id = ? AND user_id = ?", MAPPER, id, userId)
                .stream().findFirst();
    }

    public UUID insert(UUID userId, UUID kbId, String title) {
        return jdbc.queryForObject("""
                INSERT INTO chat_sessions (user_id, knowledge_base_id, title) VALUES (?, ?, ?) RETURNING id
                """, UUID.class, userId, kbId, title);
    }

    public void updateKb(UUID id, UUID kbId) {
        jdbc.update("UPDATE chat_sessions SET knowledge_base_id = ?, updated_at = now() WHERE id = ?", kbId, id);
    }

    public void updateTitle(UUID id, String title) {
        jdbc.update("UPDATE chat_sessions SET title = ?, updated_at = now() WHERE id = ?", title, id);
    }

    public void touch(UUID id) {
        jdbc.update("UPDATE chat_sessions SET updated_at = now() WHERE id = ?", id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM chat_sessions WHERE id = ?", id);
    }

    public List<ChatMessage> findMessages(UUID sessionId) {
        return jdbc.query("SELECT * FROM chat_messages WHERE session_id = ? ORDER BY created_at", MSG_MAPPER, sessionId);
    }

    public UUID insertMessage(UUID sessionId, String role, String content, String citationsJson) {
        jdbc.update("""
                INSERT INTO chat_messages (session_id, role, content, citations) VALUES (?, ?, ?, CAST(? AS jsonb))
                """, sessionId, role, content, citationsJson == null ? "[]" : citationsJson);
        touch(sessionId);
        return sessionId;
    }
}
