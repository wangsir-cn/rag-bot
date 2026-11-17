package com.example.ragbot.service;

import com.example.ragbot.config.RagProperties;
import com.example.ragbot.domain.RetrievedChunk;
import com.example.ragbot.domain.ScoredChunk;
import com.example.ragbot.dto.RetrievalOptions;
import com.example.ragbot.repository.ChunkRepositoryV2;
import com.example.ragbot.util.FullTextUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/** 阶段 4 v2：限定知识库的向量 + BM25 混合检索 + RRF/加权融合 */
@Service
public class RetrievalServiceV2 {

    private static final Logger log = LoggerFactory.getLogger(RetrievalServiceV2.class);

    private final EmbeddingService embeddingService;
    private final ChunkRepositoryV2 chunkRepository;
    private final RerankService rerankService;
    private final RagProperties properties;

    public RetrievalServiceV2(EmbeddingService embeddingService, ChunkRepositoryV2 chunkRepository,
                             RerankService rerankService, RagProperties properties) {
        this.embeddingService = embeddingService;
        this.chunkRepository = chunkRepository;
        this.rerankService = rerankService;
        this.properties = properties;
    }

    /** 本次实际生效的检索参数，回显给调试面板 */
    public record RetrievalOutcome(
            List<RetrievedChunk> chunks,
            int vectorCount, int bm25Count,
            long vectorMs, long bm25Ms, long totalMs,
            String fusion, UUID kbId,
            Map<String, Object> applied
    ) {}

    /** 合并全局默认与请求覆盖后的有效参数 */
    private record EffectiveParams(int topK, int candidateK, double similarityThreshold,
                                   String fusion, int rrfK, double vectorWeight,
                                   double bm25Weight, boolean rerankEnabled) {}

    /** 问答链路：沿用全局默认配置 */
    public RetrievalOutcome retrieve(String query, UUID kbId) {
        return retrieve(query, kbId, null);
    }

    /** 调试链路：options 中非 null 的字段覆盖全局默认 */
    public RetrievalOutcome retrieve(String query, UUID kbId, RetrievalOptions options) {
        long started = System.nanoTime();
        EffectiveParams p = resolve(options);

        float[] queryVector = embeddingService.embedForKb(query, kbId);

        long vStart = System.nanoTime();
        List<ScoredChunk> vectorHits = chunkRepository.searchByVector(
                kbId, queryVector, p.similarityThreshold(), p.candidateK());
        long vectorMs = (System.nanoTime() - vStart) / 1_000_000;

        long bStart = System.nanoTime();
        String tsQuery = FullTextUtils.buildTsQuery(query);
        List<ScoredChunk> bm25Hits = tsQuery.isBlank()
                ? List.of()
                : chunkRepository.searchByFullText(kbId, tsQuery, p.candidateK());
        long bm25Ms = (System.nanoTime() - bStart) / 1_000_000;

        List<RetrievedChunk> fused = "WEIGHTED".equalsIgnoreCase(p.fusion())
                ? weightedFusion(vectorHits, bm25Hits, p)
                : rrfFusion(vectorHits, bm25Hits, p);
        fused = rerankService.rerank(query, fused, p.rerankEnabled());
        List<RetrievedChunk> finalChunks = fused.stream().limit(p.topK()).collect(Collectors.toList());

        long totalMs = (System.nanoTime() - started) / 1_000_000;
        Map<String, Object> applied = new LinkedHashMap<>();
        applied.put("topK", p.topK());
        applied.put("candidateK", p.candidateK());
        applied.put("similarityThreshold", p.similarityThreshold());
        applied.put("fusion", p.fusion());
        applied.put("rrfK", p.rrfK());
        applied.put("vectorWeight", p.vectorWeight());
        applied.put("bm25Weight", p.bm25Weight());
        applied.put("rerankEnabled", p.rerankEnabled());
        return new RetrievalOutcome(finalChunks, vectorHits.size(), bm25Hits.size(),
                vectorMs, bm25Ms, totalMs, p.fusion(), kbId, applied);
    }

    private EffectiveParams resolve(RetrievalOptions o) {
        RagProperties.Retrieval cfg = properties.getRetrieval();
        if (o == null) {
            return new EffectiveParams(cfg.getTopK(), cfg.getCandidateK(), cfg.getSimilarityThreshold(),
                    cfg.getFusion(), cfg.getRrfK(), cfg.getVectorWeight(), cfg.getBm25Weight(),
                    cfg.getRerank().isEnabled());
        }
        return new EffectiveParams(
                positive(o.topK(), cfg.getTopK()),
                positive(o.candidateK(), cfg.getCandidateK()),
                o.similarityThreshold() != null ? o.similarityThreshold() : cfg.getSimilarityThreshold(),
                o.fusion() != null && !o.fusion().isBlank() ? o.fusion() : cfg.getFusion(),
                positive(o.rrfK(), cfg.getRrfK()),
                o.vectorWeight() != null ? o.vectorWeight() : cfg.getVectorWeight(),
                o.bm25Weight() != null ? o.bm25Weight() : cfg.getBm25Weight(),
                o.rerankEnabled() != null ? o.rerankEnabled() : cfg.getRerank().isEnabled());
    }

    private static int positive(Integer v, int fallback) {
        return v != null && v > 0 ? v : fallback;
    }

    private List<RetrievedChunk> rrfFusion(List<ScoredChunk> v, List<ScoredChunk> b, EffectiveParams p) {
        int k = Math.max(1, p.rrfK());
        double vw = p.vectorWeight();
        double bw = p.bm25Weight();
        Map<UUID, Acc> acc = new LinkedHashMap<>();
        for (int i = 0; i < v.size(); i++) {
            var c = v.get(i).chunk();
            Acc a = acc.computeIfAbsent(c.id(), id -> new Acc(c));
            a.vRank = i + 1; a.score += vw / (k + a.vRank);
        }
        for (int i = 0; i < b.size(); i++) {
            var c = b.get(i).chunk();
            Acc a = acc.computeIfAbsent(c.id(), id -> new Acc(c));
            a.bRank = i + 1; a.score += bw / (k + a.bRank);
        }
        return sorted(acc);
    }

    /**
     * 加权分数融合：对每一路的分数按「相对本路最高分的比例」归一化后加权。
     * 用 score/max 而非 min-max，避免把每路最低分强行压成 0（曾被召回的切片显示 0 分误导用户）。
     */
    private List<RetrievedChunk> weightedFusion(List<ScoredChunk> v, List<ScoredChunk> b, EffectiveParams p) {
        double vw = p.vectorWeight();
        double bw = p.bm25Weight();
        double vMax = maxScore(v), bMax = maxScore(b);
        Map<UUID, Acc> acc = new LinkedHashMap<>();
        for (int i = 0; i < v.size(); i++) {
            var hit = v.get(i);
            Acc a = acc.computeIfAbsent(hit.chunk().id(), id -> new Acc(hit.chunk()));
            a.vRank = i + 1; a.score += vw * norm(hit.score(), vMax);
        }
        for (int i = 0; i < b.size(); i++) {
            var hit = b.get(i);
            Acc a = acc.computeIfAbsent(hit.chunk().id(), id -> new Acc(hit.chunk()));
            a.bRank = i + 1; a.score += bw * norm(hit.score(), bMax);
        }
        return sorted(acc);
    }

    private List<RetrievedChunk> sorted(Map<UUID, Acc> acc) {
        List<RetrievedChunk> out = acc.values().stream()
                .map(a -> new RetrievedChunk(a.chunk, a.score, a.vRank, a.bRank))
                .collect(Collectors.toCollection(ArrayList::new));
        out.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
        return out;
    }

    /** 取一路召回中的最高原始分作为归一化基准 */
    private double maxScore(List<ScoredChunk> hits) {
        double max = 0;
        for (var h : hits) max = Math.max(max, h.score());
        return max;
    }

    /** 归一化到 (0,1]：相对本路最高分的比例；最高分=1，其余>0，不会塌成 0。 */
    private double norm(double value, double max) {
        if (max <= 1e-9) return 1.0;
        return Math.max(0, Math.min(1.0, value / max));
    }

    private static class Acc {
        final com.example.ragbot.domain.ChunkRecordV2 chunk;
        double score; int vRank = -1, bRank = -1;
        Acc(com.example.ragbot.domain.ChunkRecordV2 c) { this.chunk = c; }
    }
}
