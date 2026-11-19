package com.example.ragbot.service;

import com.example.ragbot.domain.AiModelConfig;
import com.example.ragbot.repository.AiModelRepository;
import com.example.ragbot.repository.KnowledgeBaseRepository;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Embedding 入口：按知识库绑定的向量模型动态调用。
 * 同一知识库内所有 chunk 与 query 必须用同一模型，检索时才可比。
 */
@Service
public class EmbeddingService {

    /** 单次请求提交的文本条数上限。不同厂商差异很大（如 qwen3-embedding 系列限制 35 条/次）。 */
    private final int batchSize;
    /** 单次请求的字符总量上限，防止长切片撞上厂商的 token 限制。 */
    private final int maxBatchChars;

    private final ModelClientFactory clientFactory;
    private final KnowledgeBaseRepository kbRepository;
    private final AiModelRepository modelRepository;

    public EmbeddingService(ModelClientFactory clientFactory,
                            KnowledgeBaseRepository kbRepository,
                            AiModelRepository modelRepository,
                            @Value("${rag.embedding.batch-size:16}") int batchSize,
                            @Value("${rag.embedding.batch-chars:8000}") int maxBatchChars) {
        this.clientFactory = clientFactory;
        this.kbRepository = kbRepository;
        this.modelRepository = modelRepository;
        this.batchSize = Math.max(1, batchSize);
        this.maxBatchChars = Math.max(1, maxBatchChars);
    }

    /** 取知识库绑定的向量模型配置 */
    public AiModelConfig modelForKb(UUID kbId) {
        var kb = kbRepository.findById(kbId)
                .orElseThrow(() -> new IllegalStateException("知识库不存在: " + kbId));
        return modelRepository.findById(kb.embeddingModelId())
                .orElseThrow(() -> new IllegalStateException("知识库绑定的向量模型不存在: " + kb.embeddingModelId()));
    }

    public float[] embed(String text, AiModelConfig cfg) {
        return clientFactory.embeddingModel(cfg).embed(text);
    }

    public float[] embedForKb(String text, UUID kbId) {
        return embed(text, modelForKb(kbId));
    }

    public List<float[]> embedBatch(List<String> texts, AiModelConfig cfg) {
        EmbeddingModel model = clientFactory.embeddingModel(cfg);
        List<float[]> result = new ArrayList<>(texts.size());
        int i = 0;
        while (i < texts.size()) {
            int end = i;
            int chars = 0;
            while (end < texts.size()) {
                int len = texts.get(end) == null ? 0 : texts.get(end).length();
                if (end > i && (end - i >= batchSize || chars + len > maxBatchChars)) break;
                chars += len;
                end++;
            }
            result.addAll(embedChunk(model, texts.subList(i, end)));
            i = end;
        }
        return result;
    }

    /**
     * 配置值仍可能超出厂商实际限制（条数或 token）。
     * 一旦上游明确报批次过大，就把当前批次二分后重试，避免整篇文档失败。
     */
    private List<float[]> embedChunk(EmbeddingModel model, List<String> batch) {
        try {
            return model.embed(batch);
        } catch (RuntimeException e) {
            if (batch.size() > 1 && isOversizedRequest(e)) {
                int mid = batch.size() / 2;
                List<float[]> out = new ArrayList<>(batch.size());
                out.addAll(embedChunk(model, batch.subList(0, mid)));
                out.addAll(embedChunk(model, batch.subList(mid, batch.size())));
                return out;
            }
            throw e;
        }
    }

    private boolean isOversizedRequest(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) continue;
            String lower = msg.toLowerCase();
            if (lower.contains("batch size") || lower.contains("batch_size")
                    || lower.contains("too many tokens") || lower.contains("token limit")
                    || lower.contains("maximum context") || lower.contains("too long")) {
                return true;
            }
        }
        return false;
    }
}
