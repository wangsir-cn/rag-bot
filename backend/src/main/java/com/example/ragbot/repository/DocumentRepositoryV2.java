package com.example.ragbot.repository;

import com.example.ragbot.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentRepositoryV2 {

    private final JdbcTemplate jdbc;
    public DocumentRepositoryV2(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<DocumentRecord> MAPPER = (rs, n) -> new DocumentRecord(
            UUID.fromString(rs.getString("id")),
            UUID.fromString(rs.getString("knowledge_base_id")),
            UUID.fromString(rs.getString("user_id")),
            rs.getString("filename"),
            rs.getString("original_filename"),
            rs.getString("file_path"),
            rs.getLong("file_size"),
            rs.getString("mime_type"),
            rs.getString("file_kind"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getString("error_message"),
            rs.getInt("chunk_count"),
            com.example.ragbot.util.JsonbUtils.toMap(rs.getString("metadata")),
            rs.getObject("created_at", java.time.OffsetDateTime.class)
    );

    public List<DocumentRecord> findByKb(UUID kbId) {
        return jdbc.query("SELECT * FROM documents WHERE knowledge_base_id = ? ORDER BY created_at DESC", MAPPER, kbId);
    }

    public Optional<DocumentRecord> findById(UUID id) {
        return jdbc.query("SELECT * FROM documents WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public UUID insert(UUID kbId, UUID userId, String filename, String originalFilename, String filePath,
                       long fileSize, String mimeType, String fileKind, String metadataJson) {
        return jdbc.queryForObject("""
                INSERT INTO documents (knowledge_base_id, user_id, filename, original_filename, file_path,
                                       file_size, mime_type, file_kind, metadata)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb)) RETURNING id
                """, UUID.class, kbId, userId, filename, originalFilename, filePath,
                fileSize, mimeType, fileKind, metadataJson);
    }

    public void updateStatus(UUID id, DocumentStatus status, String errorMessage) {
        jdbc.update("""
                UPDATE documents SET status = ?, error_message = ?, updated_at = now() WHERE id = ?
                """, status.name(), errorMessage, id);
    }

    public void updateChunkCount(UUID id, int count) {
        jdbc.update("UPDATE documents SET chunk_count = ?, updated_at = now() WHERE id = ?", count, id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM documents WHERE id = ?", id);
    }
}
