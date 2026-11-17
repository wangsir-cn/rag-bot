package com.example.ragbot.service;

import com.example.ragbot.domain.AppUser;
import com.example.ragbot.dto.KbDtos;
import com.example.ragbot.repository.AiModelRepository;
import com.example.ragbot.repository.ChunkRepositoryV2;
import com.example.ragbot.repository.DocumentRepositoryV2;
import com.example.ragbot.repository.KnowledgeBaseRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class KnowledgeBaseService {

    private final KnowledgeBaseRepository kbRepo;
    private final AiModelRepository modelRepo;
    private final DocumentRepositoryV2 docRepo;
    private final ChunkRepositoryV2 chunkRepo;
    private final StorageService storageService;

    public KnowledgeBaseService(KnowledgeBaseRepository kbRepo, AiModelRepository modelRepo,
                                DocumentRepositoryV2 docRepo, ChunkRepositoryV2 chunkRepo,
                                StorageService storageService) {
        this.kbRepo = kbRepo;
        this.modelRepo = modelRepo;
        this.docRepo = docRepo;
        this.chunkRepo = chunkRepo;
        this.storageService = storageService;
    }

    public List<KbDtos.KbVO> list(UUID userId) {
        return kbRepo.findByUser(userId).stream().map(kb -> {
            var model = modelRepo.findById(kb.embeddingModelId());
            String modelName = model.map(m -> m.name()).orElse("已删除");
            int docCount = docRepo.findByKb(kb.id()).size();
            int chunkCount = docRepo.findByKb(kb.id()).stream()
                    .mapToInt(d -> d.chunkCount()).sum();
            String created = kb.createdAt() == null ? "" : kb.createdAt().toString();
            return KbDtos.KbVO.from(kb, modelName, docCount, chunkCount, created);
        }).toList();
    }

    public KbDtos.KbVO create(AppUser user, KbDtos.KbRequest req) {
        if (req.name() == null || req.name().isBlank()) throw new IllegalArgumentException("知识库名称不能为空");
        if (kbRepo.existsByNameAndUser(req.name(), user.id())) throw new IllegalArgumentException("知识库名称已存在");
        if (req.embeddingModelId() == null) throw new IllegalArgumentException("必须绑定向量模型才能创建知识库");
        var model = modelRepo.findById(req.embeddingModelId())
                .orElseThrow(() -> new IllegalArgumentException("向量模型不存在"));
        if (!model.userId().equals(user.id())) throw new IllegalStateException("无权使用该模型");
        if (!model.isEmbedding()) throw new IllegalArgumentException("绑定的模型必须是向量模型(EMBEDDING)");

        String strategy = (req.chunkStrategy() == null || req.chunkStrategy().isBlank()) ? "SEMANTIC" : req.chunkStrategy();
        int size = req.chunkSize() == null ? 800 : req.chunkSize();
        int overlap = req.chunkOverlap() == null ? 120 : req.chunkOverlap();
        UUID id = kbRepo.insert(user.id(), req.name(), req.description(), req.embeddingModelId(),
                strategy, size, overlap);
        var kb = kbRepo.findById(id).orElseThrow();
        return KbDtos.KbVO.from(kb, model.name(), 0, 0, kb.createdAt().toString());
    }

    public KbDtos.KbVO update(UUID userId, UUID kbId, KbDtos.KbRequest req) {
        var kb = kbRepo.findByIdAndUser(kbId, userId).orElseThrow(() -> new IllegalStateException("知识库不存在或无权访问"));
        var model = modelRepo.findById(req.embeddingModelId())
                .orElseThrow(() -> new IllegalArgumentException("向量模型不存在"));
        if (!model.userId().equals(userId)) throw new IllegalStateException("无权使用该模型");
        kbRepo.update(kbId, req.name(), req.description(), req.embeddingModelId(),
                req.chunkStrategy(), req.chunkSize(), req.chunkOverlap());
        var updated = kbRepo.findById(kbId).orElseThrow();
        return KbDtos.KbVO.from(updated, model.name(),
                docRepo.findByKb(kbId).size(),
                docRepo.findByKb(kbId).stream().mapToInt(d -> d.chunkCount()).sum(),
                updated.createdAt().toString());
    }

    public void delete(UUID userId, UUID kbId) {
        kbRepo.findByIdAndUser(kbId, userId).orElseThrow(() -> new IllegalStateException("知识库不存在或无权访问"));
        // 先清理物理文件（本地磁盘或 MinIO 对象），避免删除知识库后留下孤立存储对象
        for (var doc : docRepo.findByKb(kbId)) {
            try {
                storageService.delete(doc.filePath());
            } catch (Exception e) {
                // 单个文件删除失败不阻断整个知识库的删除
                org.slf4j.LoggerFactory.getLogger(KnowledgeBaseService.class)
                        .warn("Failed to delete stored file for doc {}: {}", doc.id(), doc.filePath(), e);
            }
        }
        kbRepo.delete(kbId);
    }
}
