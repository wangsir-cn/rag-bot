package com.example.ragbot.repository;

import com.example.ragbot.domain.KnowledgeBase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class KnowledgeBaseRepository {

    private final JdbcTemplate jdbc;
    public KnowledgeBaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<KnowledgeBase> MAPPER = (rs, n) -> new KnowledgeBase(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("name"),
            rs.getString("description"),
            UUID.fromString(rs.getString("embedding_model_id")),
            rs.getString("chunk_strategy"),
            rs.getInt("chunk_size"),
            rs.getInt("chunk_overlap"),
            rs.getObject("created_at", java.time.OffsetDateTime.class),
            rs.getObject("updated_at", java.time.OffsetDateTime.class)
    );

    public List<KnowledgeBase> findByUser(UUID userId) {
        return jdbc.query("SELECT * FROM knowledge_bases WHERE user_id = ? ORDER BY created_at DESC", MAPPER, userId);
    }

    public Optional<KnowledgeBase> findById(UUID id) {
        return jdbc.query("SELECT * FROM knowledge_bases WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 带用户归属校验的查询，防止越权访问他人知识库 */
    public Optional<KnowledgeBase> findByIdAndUser(UUID id, UUID userId) {
        return jdbc.query("SELECT * FROM knowledge_bases WHERE id = ? AND user_id = ?", MAPPER, id, userId)
                .stream().findFirst();
    }

    public boolean existsByNameAndUser(String name, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT count(*) > 0 FROM knowledge_bases WHERE name = ? AND user_id = ?", Boolean.class, name, userId));
    }

    public UUID insert(UUID userId, String name, String description, UUID embeddingModelId,
                       String strategy, int chunkSize, int chunkOverlap) {
        return jdbc.queryForObject("""
                INSERT INTO knowledge_bases (user_id, name, description, embedding_model_id, chunk_strategy, chunk_size, chunk_overlap)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, userId, name, description, embeddingModelId, strategy, chunkSize, chunkOverlap);
    }

    public void update(UUID id, String name, String description, UUID embeddingModelId,
                       String strategy, int chunkSize, int chunkOverlap) {
        jdbc.update("""
                UPDATE knowledge_bases SET name=?, description=?, embedding_model_id=?,
                       chunk_strategy=?, chunk_size=?, chunk_overlap=?, updated_at=now() WHERE id=?
                """, name, description, embeddingModelId, strategy, chunkSize, chunkOverlap, id);
    }

    public void delete(UUID id) {
        // 缓存、文档、切片均通过外键 CASCADE 清理
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", id);
    }
}
