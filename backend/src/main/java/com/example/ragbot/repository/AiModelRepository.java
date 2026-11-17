package com.example.ragbot.repository;

import com.example.ragbot.domain.AiModelConfig;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AiModelRepository {

    private final JdbcTemplate jdbc;
    public AiModelRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<AiModelConfig> MAPPER = (rs, n) -> new AiModelConfig(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("name"),
            rs.getString("model_type"),
            rs.getString("base_url"),
            rs.getString("api_key"),
            rs.getString("model_name"),
            rs.getObject("dimensions", Integer.class),
            rs.getBoolean("is_default"),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
    );

    public List<AiModelConfig> findByUser(UUID userId) {
        return jdbc.query("SELECT * FROM ai_models WHERE user_id = ? ORDER BY created_at DESC", MAPPER, userId);
    }

    public List<AiModelConfig> findByUserAndType(UUID userId, String type) {
        return jdbc.query("SELECT * FROM ai_models WHERE user_id = ? AND model_type = ? ORDER BY created_at DESC",
                MAPPER, userId, type);
    }

    public Optional<AiModelConfig> findById(UUID id) {
        return jdbc.query("SELECT * FROM ai_models WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 找用户某类型的默认模型 */
    public Optional<AiModelConfig> findDefault(UUID userId, String type) {
        return jdbc.query("SELECT * FROM ai_models WHERE user_id = ? AND model_type = ? AND is_default",
                MAPPER, userId, type).stream().findFirst();
    }

    public UUID insert(UUID userId, String name, String type, String baseUrl, String apiKey,
                       String modelName, Integer dimensions, boolean isDefault) {
        if (isDefault) {
            // 先取消同类型其它默认，避免唯一索引冲突
            jdbc.update("UPDATE ai_models SET is_default = FALSE WHERE user_id = ? AND model_type = ? AND is_default",
                    userId, type);
        }
        return jdbc.queryForObject("""
                INSERT INTO ai_models (user_id, name, model_type, base_url, api_key, model_name, dimensions, is_default)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, userId, name, type, baseUrl, apiKey, modelName, dimensions, isDefault);
    }

    public void update(UUID id, String name, String type, String baseUrl, String apiKey,
                       String modelName, Integer dimensions, boolean isDefault) {
        if (isDefault) {
            var cur = findById(id).orElseThrow();
            jdbc.update("UPDATE ai_models SET is_default = FALSE WHERE user_id = ? AND model_type = ? AND is_default AND id <> ?",
                    cur.userId(), type, id);
        }
        jdbc.update("""
                UPDATE ai_models SET name=?, model_type=?, base_url=?, api_key=?, model_name=?,
                       dimensions=?, is_default=?, updated_at=now() WHERE id=?
                """, name, type, baseUrl, apiKey, modelName, dimensions, isDefault, id);
    }

    public void setDefault(UUID id, UUID userId, String type) {
        jdbc.update("UPDATE ai_models SET is_default = FALSE WHERE user_id = ? AND model_type = ? AND is_default",
                userId, type);
        jdbc.update("UPDATE ai_models SET is_default = TRUE, updated_at=now() WHERE id = ?", id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM ai_models WHERE id = ?", id);
    }

    public long countByUser(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM ai_models WHERE user_id = ?", Long.class, userId);
    }

    public long countKbUsage(UUID modelId) {
        return jdbc.queryForObject("SELECT count(*) FROM knowledge_bases WHERE embedding_model_id = ?",
                Long.class, modelId);
    }
}
