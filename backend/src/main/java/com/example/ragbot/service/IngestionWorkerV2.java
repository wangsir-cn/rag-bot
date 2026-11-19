package com.example.ragbot.service;

import com.example.ragbot.domain.*;
import com.example.ragbot.repository.ChunkRepositoryV2;
import com.example.ragbot.repository.DocumentRepositoryV2;
import com.example.ragbot.repository.KnowledgeBaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v2 异步摄入：解析 -> 分块 -> 按知识库绑定模型向量化 -> 入库。
 * TEXT 文件：走 ChunkingService 切片策略；QA 文件：每一行 = 1 个 QA 切片。
 */
@Service
public class IngestionWorkerV2 {

    private static final Logger log = LoggerFactory.getLogger(IngestionWorkerV2.class);

    private final DocumentRepositoryV2 documentRepository;
    private final KnowledgeBaseRepository kbRepository;
    private final ChunkRepositoryV2 chunkRepository;
    private final StorageService storageService;
    private final ParsingService parsingService;
    private final QaParsingService qaParsingService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;

    public IngestionWorkerV2(DocumentRepositoryV2 documentRepository,
                             KnowledgeBaseRepository kbRepository,
                             ChunkRepositoryV2 chunkRepository,
                             StorageService storageService,
                             ParsingService parsingService,
                             QaParsingService qaParsingService,
                             ChunkingService chunkingService,
                             EmbeddingService embeddingService) {
        this.documentRepository = documentRepository;
        this.kbRepository = kbRepository;
        this.chunkRepository = chunkRepository;
        this.storageService = storageService;
        this.parsingService = parsingService;
        this.qaParsingService = qaParsingService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
    }

    @Async("ingestionExecutor")
    public void process(UUID documentId) {
        try {
            DocumentRecord doc = documentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException("文档不存在: " + documentId));
            KnowledgeBase kb = kbRepository.findById(doc.knowledgeBaseId())
                    .orElseThrow(() -> new IllegalStateException("知识库不存在"));

            documentRepository.updateStatus(documentId, DocumentStatus.PARSING, null);
            Path filePath = storageService.resolve(doc.filePath());

            List<PendingChunk> pending = new ArrayList<>();
            Map<String, Object> baseMeta = new HashMap<>(doc.metadata());
            baseMeta.put("docName", doc.originalFilename());
            baseMeta.put("docType", doc.fileKind());
            baseMeta.put("documentId", doc.id().toString());

            if ("QA".equalsIgnoreCase(doc.fileKind())) {
                // QA 文件：一行一切片，且同一文件只支持 QA 切片
                List<QaParsingService.QaRow> rows = qaParsingService.parse(filePath);
                if (rows.isEmpty()) throw new IllegalStateException(
                        "QA 文件未解析到有效问答对（需要两列：问题、答案；Excel 取第一个工作表 A/B 列）");
                int idx = 0;
                for (var row : rows) {
                    Map<String, Object> meta = new HashMap<>(baseMeta);
                    meta.put("chunkIndex", idx);
                    pending.add(new PendingChunk(idx++, "QA", row.question(), row.answer(), meta));
                }
            } else {
                // 文本文件：解析 + 切片策略
                String text = parsingService.parse(filePath);
                if (text.isBlank()) throw new IllegalStateException("未能从文档提取到文本，可能是扫描件或加密文件");
                documentRepository.updateStatus(documentId, DocumentStatus.CHUNKING, null);
                List<Chunk> chunks = chunkingService.chunk(documentId, text, baseMeta,
                        kb.chunkStrategy(), kb.chunkSize(), kb.chunkOverlap());
                if (chunks.isEmpty()) throw new IllegalStateException("分块结果为空");
                for (Chunk c : chunks) {
                    Map<String, Object> meta = new HashMap<>(c.metadata());
                    pending.add(new PendingChunk(c.chunkIndex(), "TEXT", c.content(), null, meta));
                }
            }

            documentRepository.updateStatus(documentId, DocumentStatus.EMBEDDING, null);
            // 向量化：QA 用「问题+答案」，TEXT 用正文
            List<String> embedTexts = pending.stream()
                    .map(p -> "QA".equals(p.type) ? p.content + " " + (p.answer == null ? "" : p.answer) : p.content)
                    .toList();
            AiModelConfig embModel = embeddingService.modelForKb(doc.knowledgeBaseId());
            List<float[]> vectors = embeddingService.embedBatch(embedTexts, embModel);

            List<ChunkRepositoryV2.EmbeddedChunkV2> embedded = new ArrayList<>(pending.size());
            for (int i = 0; i < pending.size(); i++) {
                PendingChunk p = pending.get(i);
                embedded.add(new ChunkRepositoryV2.EmbeddedChunkV2(
                        documentId, doc.knowledgeBaseId(), p.index, p.type,
                        p.content, p.answer, p.metadata, vectors.get(i)));
            }
            int written = chunkRepository.insertBatch(embedded);

            documentRepository.updateChunkCount(documentId, written);
            documentRepository.updateStatus(documentId, DocumentStatus.READY, null);
            log.info("文档处理完成 docId={} type={} chunks={}", documentId, doc.fileKind(), written);
        } catch (Exception e) {
            log.error("文档处理失败 docId={}", documentId, e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            documentRepository.updateStatus(documentId, DocumentStatus.FAILED,
                    message.length() > 500 ? message.substring(0, 500) : message);
        }
    }

    private record PendingChunk(int index, String type, String content, String answer,
                                Map<String, Object> metadata) {}
}
