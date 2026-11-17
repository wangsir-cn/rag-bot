package com.example.ragbot.service;

import com.example.ragbot.config.RagProperties;
import com.example.ragbot.domain.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 可选：Cross-Encoder Reranker。任何兼容 /v1/rerank 的服务（DashScope / Xinference / vLLM）均可接入。 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RagProperties properties;

    public RerankService(RagProperties properties) {
        this.properties = properties;
    }

    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates) {
        return rerank(query, candidates, null);
    }

    /** 调试场景可用 enabledOverride 临时覆盖全局 rerank 开关 */
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, Boolean enabledOverride) {
        RagProperties.Rerank config = properties.getRetrieval().getRerank();
        boolean enabled = enabledOverride != null ? enabledOverride : config.isEnabled();
        if (!enabled || candidates.size() <= 1) return candidates;

        try {
            List<String> documents = candidates.stream()
                    .map(c -> c.chunk().content()).toList();
            Map<String, Object> body = new HashMap<>();
            body.put("model", config.getModel());
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", Math.min(config.getTopK(), candidates.size()));

            String baseUrl = config.getBaseUrl().replaceAll("/+$", "");
            RestClient client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("Authorization", "Bearer " + config.getApiKey())
                    .defaultHeader("Content-Type", "application/json")
                    .build();

            String response = client.post()
                    .uri("/v1/rerank")
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = MAPPER.readTree(response);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) return candidates;

            List<RetrievedChunk> reranked = new ArrayList<>();
            for (JsonNode node : results) {
                int index = node.path("index").asInt(-1);
                double score = node.path("relevance_score").asDouble(0.0);
                if (index >= 0 && index < candidates.size()) {
                    RetrievedChunk original = candidates.get(index);
                    reranked.add(new RetrievedChunk(original.chunk(), score,
                            original.vectorRank(), original.bm25Rank()));
                }
            }
            reranked.sort(Comparator.comparingDouble(RetrievedChunk::score).reversed());
            return reranked.isEmpty() ? candidates : reranked;
        } catch (Exception e) {
            // Reranker 是增强项，失败时降级为原融合结果，不能让整个问答不可用
            log.warn("Rerank 调用失败，使用原融合结果: {}", e.getMessage());
            return candidates;
        }
    }
}
