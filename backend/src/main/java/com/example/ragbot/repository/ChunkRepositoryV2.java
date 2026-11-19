package com.example.ragbot.repository;

import com.example.ragbot.domain.ChunkRecordV2;
import com.example.ragbot.domain.ScoredChunk;
import com.example.ragbot.util.FullTextUtils;
import com.example.ragbot.util.JsonbUtils;
import com.example.ragbot.util.VectorUtils;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class ChunkRepositoryV2 {

    private final JdbcTemplate jdbc;
    public ChunkRepositoryV2(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 待写入的切片 + 向量 */
    public record EmbeddedChunkV2(UUID documentId, UUID kbId, int index, String chunkType,
                                  String content, String answer, java.util.Map<String, Object> metadata,
                                  float[] embedding) {}

    private static final String BASE_SELECT = """
            SELECT c.id, c.document_id, c.knowledge_base_id, c.chunk_index, c.chunk_type,
                   c.content, c.answer, c.metadata, c.created_at
            """;

    private static org.springframework.jdbc.core.RowMapper<ChunkRecordV2> mapper() {
        return (rs, n) -> new ChunkRecordV2(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("document_id")),
                UUID.fromString(rs.getString("knowledge_base_id")),
                rs.getInt("chunk_index"),
                rs.getString("chunk_type"),
                rs.getString("content"),
                rs.getString("answer"),
                JsonbUtils.toMap(rs.getString("metadata")),
                rs.getObject("created_at", OffsetDateTime.class)
        );
    }

    public int insertBatch(List<EmbeddedChunkV2> chunks) {
        String sql = """
                INSERT INTO chunks (document_id, knowledge_base_id, chunk_index, chunk_type,
                                    content, answer, search_text, metadata, embedding)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?::vector)
                ON CONFLICT (document_id, chunk_index) DO NOTHING
                """;
        int[] counts = jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override public void setValues(PreparedStatement ps, int i) throws SQLException {
                var c = chunks.get(i);
                ps.setObject(1, c.documentId(), java.sql.Types.OTHER);
                ps.setObject(2, c.kbId(), java.sql.Types.OTHER);
                ps.setInt(3, c.index());
                ps.setString(4, c.chunkType());
                ps.setString(5, c.content());
                ps.setString(6, c.answer());
                // QA 切片把问题与答案都纳入全文检索；TEXT 只有正文
                String searchSrc = "QA".equalsIgnoreCase(c.chunkType())
                        ? c.content() + " " + (c.answer() == null ? "" : c.answer())
                        : c.content();
                ps.setString(7, FullTextUtils.buildSearchText(searchSrc));
                ps.setString(8, JsonbUtils.toJson(c.metadata()));
                ps.setString(9, VectorUtils.toPgLiteral(c.embedding()));
            }
            @Override public int getBatchSize() { return chunks.size(); }
        });
        int total = 0;
        for (int c : counts) total += Math.max(c, 0);
        return total;
    }

    /** 向量检索：限定知识库，余弦距离 */
    public List<ScoredChunk> searchByVector(UUID kbId, float[] queryVector,
                                            double minSimilarity, int limit) {
        String sql = BASE_SELECT + """
                , 1 - (c.embedding <=> ?::vector) AS score
                FROM chunks c
                WHERE c.knowledge_base_id = ?::uuid
                  AND (1 - (c.embedding <=> ?::vector)) >= ?
                ORDER BY c.embedding <=> ?::vector LIMIT ?
                """;
        String literal = VectorUtils.toPgLiteral(queryVector);
        return jdbc.query(sql, (rs, n) -> new ScoredChunk(mapper().mapRow(rs, n), rs.getDouble("score")),
                literal, kbId, literal, minSimilarity, literal, limit);
    }

    /** BM25 全文检索：限定知识库 */
    public List<ScoredChunk> searchByFullText(UUID kbId, String tsQuery, int limit) {
        String sql = BASE_SELECT + """
                , ts_rank_cd(c.content_tsv, q.query) AS score
                FROM chunks c
                CROSS JOIN (SELECT to_tsquery('simple', ?) AS query) q
                WHERE c.knowledge_base_id = ?::uuid
                  AND c.content_tsv @@ q.query
                  AND ts_rank_cd(c.content_tsv, q.query) > 0
                ORDER BY score DESC LIMIT ?
                """;
        return jdbc.query(sql, (rs, n) -> new ScoredChunk(mapper().mapRow(rs, n), rs.getDouble("score")),
                tsQuery, kbId, limit);
    }

    public List<ChunkRecordV2> findByDocument(UUID documentId) {
        return jdbc.query(BASE_SELECT + " FROM chunks c WHERE c.document_id = ?::uuid ORDER BY c.chunk_index",
                mapper(), documentId);
    }

    /** 统计某文档切片数 */
    public int countByDocument(UUID documentId) {
        return jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?::uuid",
                Integer.class, documentId);
    }

    /** 手动新增切片（TEXT/QA），同步生成向量入库 */
    public UUID insertOne(EmbeddedChunkV2 c) {
        String searchSrc = "QA".equalsIgnoreCase(c.chunkType())
                ? c.content() + " " + (c.answer() == null ? "" : c.answer())
                : c.content();
        return jdbc.queryForObject("""
                INSERT INTO chunks (document_id, knowledge_base_id, chunk_index, chunk_type,
                                    content, answer, search_text, metadata, embedding)
                VALUES (?::uuid, ?::uuid, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?::vector) RETURNING id
                """, UUID.class, c.documentId(), c.kbId(), c.index(), c.chunkType(), c.content(),
                c.answer(), FullTextUtils.buildSearchText(searchSrc),
                JsonbUtils.toJson(c.metadata()), VectorUtils.toPgLiteral(c.embedding()));
    }

    public void delete(UUID chunkId) {
        jdbc.update("DELETE FROM chunks WHERE id = ?::uuid", chunkId);
    }

    public void update(UUID chunkId, String content, String answer) {
        String searchSrc = "QA".equalsIgnoreCase(answer == null ? "TEXT" : "QA")
                ? content + " " + (answer == null ? "" : answer) : content;
        jdbc.update("UPDATE chunks SET content = ?, answer = ?, search_text = ? WHERE id = ?::uuid",
                content, answer, FullTextUtils.buildSearchText(searchSrc), chunkId);
    }

    /** 找到文档下一个可用 chunk_index */
    public int nextIndex(UUID documentId) {
        Integer v = jdbc.queryForObject(
                "SELECT COALESCE(max(chunk_index), -1) + 1 FROM chunks WHERE document_id = ?::uuid",
                Integer.class, documentId);
        return v == null ? 0 : v;
    }

    /** 取切片详情（含所属文档名）用于跳转展示 */
    public java.util.Optional<ChunkRecordV2> findById(UUID id) {
        return jdbc.query(BASE_SELECT + " FROM chunks c WHERE c.id = ?::uuid", mapper(), id)
                .stream().findFirst();
    }
}
