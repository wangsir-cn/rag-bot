package com.example.ragbot.service;

import com.example.ragbot.domain.AiModelConfig;
import com.example.ragbot.repository.AiModelRepository;
import com.example.ragbot.repository.KnowledgeBaseRepository;
import org.springframework.ai.embedding.EmbeddingModel;
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

    private static final int API_BATCH_SIZE = 64;

    private final ModelClientFactory clientFactory;
    private final KnowledgeBaseRepository kbRepository;
    private final AiModelRepository modelRepository;

    public EmbeddingService(ModelClientFactory clientFactory,
                            KnowledgeBaseRepository kbRepository,
                            AiModelRepository modelRepository) {
        this.clientFactory = clientFactory;
        this.kbRepository = kbRepository;
        this.modelRepository = modelRepository;
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
        for (int i = 0; i < texts.size(); i += API_BATCH_SIZE) {
            int end = Math.min(i + API_BATCH_SIZE, texts.size());
            result.addAll(model.embed(texts.subList(i, end)));
        }
        return result;
    }
}
