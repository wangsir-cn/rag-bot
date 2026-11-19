package com.example.ragbot.service;

import com.example.ragbot.domain.*;
import com.example.ragbot.dto.KbDtos;
import com.example.ragbot.dto.UploadResponse;
import com.example.ragbot.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class DocumentServiceV2 {

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    private final DocumentRepositoryV2 docRepo;
    private final KnowledgeBaseRepository kbRepo;
    private final ChunkRepositoryV2 chunkRepo;
    private final AiModelRepository modelRepo;
    private final StorageService storageService;
    private final IngestionWorkerV2 worker;
    private final EmbeddingService embeddingService;

    public DocumentServiceV2(DocumentRepositoryV2 docRepo, KnowledgeBaseRepository kbRepo,
                             ChunkRepositoryV2 chunkRepo, AiModelRepository modelRepo,
                             StorageService storageService, IngestionWorkerV2 worker,
                             EmbeddingService embeddingService) {
        this.docRepo = docRepo;
        this.kbRepo = kbRepo;
        this.chunkRepo = chunkRepo;
        this.modelRepo = modelRepo;
        this.storageService = storageService;
        this.worker = worker;
        this.embeddingService = embeddingService;
    }

    public UploadResponse upload(UUID userId, UUID kbId, MultipartFile file, String requestedKind) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("文件不能为空");
        if (file.getSize() > MAX_FILE_SIZE) throw new IllegalArgumentException("文件大小不能超过 50MB");

        var kb = kbRepo.findByIdAndUser(kbId, userId)
                .orElseThrow(() -> new IllegalStateException("知识库不存在或无权访问"));
        // 建库已强制绑定向量模型，这里再校验一次确保没被删除
        if (modelRepo.findById(kb.embeddingModelId()).isEmpty()) {
            throw new IllegalStateException("知识库未绑定有效的向量模型，请先配置");
        }

        String original = sanitize(file.getOriginalFilename());
        String extension = extensionOf(original);
        // 前端明确指定 TEXT/QA；未指定时按扩展名推断
        String fileKind = (requestedKind != null && !requestedKind.isBlank())
                ? requestedKind.toUpperCase() : inferFileKind(extension);
        if (!"TEXT".equals(fileKind) && !"QA".equals(fileKind)) {
            throw new IllegalArgumentException("文件类型只能是 TEXT 或 QA");
        }
        // TEXT 与 QA 可以共存于同一知识库：检索时会按 chunkType 分别拼装上下文。

        String storedName = UUID.randomUUID() + extension;
        String relativePath = storageService.save(file, storedName);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("extension", extension);
        metadata.put("contentType", file.getContentType());
        UUID id = docRepo.insert(kbId, userId, storedName, original, relativePath,
                file.getSize(), file.getContentType(), fileKind, com.example.ragbot.util.JsonbUtils.toJson(metadata));

        worker.process(id);
        return new UploadResponse(id, original, file.getSize(), DocumentStatus.PENDING.name(),
                "上传成功，正在后台解析");
    }

    /** 判断是文本文件还是 QA 文件：文件名为 qa 前缀或 .csv/.tsv 视为 QA */
    private String inferFileKind(String ext) {
        return switch (ext) {
            case ".pdf", ".doc", ".docx", ".txt", ".md", ".markdown" -> "TEXT";
            case ".csv", ".tsv" -> "QA";
            default -> "OTHER";
        };
    }

    public List<Map<String, Object>> listDocs(UUID userId, UUID kbId) {
        kbRepo.findByIdAndUser(kbId, userId).orElseThrow(() -> new IllegalStateException("知识库不存在或无权访问"));
        return docRepo.findByKb(kbId).stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id().toString());
            m.put("originalFilename", d.originalFilename());
            m.put("fileKind", d.fileKind());
            m.put("fileSize", d.fileSize());
            m.put("status", d.status().name());
            m.put("chunkCount", d.chunkCount());
            m.put("errorMessage", d.errorMessage());
            m.put("createdAt", d.createdAt() == null ? "" : d.createdAt().toString());
            return m;
        }).collect(Collectors.toList());
    }

    public void deleteDoc(UUID userId, UUID docId) throws IOException {
        var doc = docRepo.findById(docId).orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        kbRepo.findByIdAndUser(doc.knowledgeBaseId(), userId)
                .orElseThrow(() -> new IllegalStateException("无权删除此文档"));
        storageService.delete(doc.filePath());
        docRepo.delete(docId);
    }

    public void reprocess(UUID userId, UUID docId) {
        var doc = docRepo.findById(docId).orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        kbRepo.findByIdAndUser(doc.knowledgeBaseId(), userId)
                .orElseThrow(() -> new IllegalStateException("无权操作此文档"));
        docRepo.updateStatus(docId, DocumentStatus.PENDING, null);
        worker.process(docId);
    }

    /** 手动创建切片（TEXT 或 QA），用知识库绑定模型生成向量 */
    public KbDtos.ChunkVO createChunk(UUID userId, UUID kbId, String chunkType,
                                       String content, String answer) {
        var kb = kbRepo.findByIdAndUser(kbId, userId)
                .orElseThrow(() -> new IllegalStateException("知识库不存在或无权访问"));
        if (!"TEXT".equalsIgnoreCase(chunkType) && !"QA".equalsIgnoreCase(chunkType)) {
            throw new IllegalArgumentException("切片类型必须是 TEXT 或 QA");
        }
        if (content == null || content.isBlank()) throw new IllegalArgumentException("内容不能为空");
        if ("QA".equalsIgnoreCase(chunkType) && (answer == null || answer.isBlank())) {
            throw new IllegalArgumentException("QA 切片必须填写答案");
        }
        // 手动切片归属一个特殊文档（用户维度、每个 kb 一条）
        UUID manualDocId = ensureManualDocument(kbId, userId, chunkType);
        int idx = chunkRepo.nextIndex(manualDocId);
        String embedText = "QA".equalsIgnoreCase(chunkType) ? content + " " + answer : content;
        float[] vector = embeddingService.embedForKb(embedText, kbId);
        Map<String, Object> meta = new HashMap<>();
        meta.put("manual", true);
        meta.put("chunkIndex", idx);
        UUID chunkId = chunkRepo.insertOne(new ChunkRepositoryV2.EmbeddedChunkV2(
                manualDocId, kbId, idx, chunkType.toUpperCase(), content, answer, meta, vector));
        docRepo.updateChunkCount(manualDocId, chunkRepo.countByDocument(manualDocId));
        return toChunkVO(chunkRepo.findById(chunkId).orElseThrow());
    }

    private UUID ensureManualDocument(UUID kbId, UUID userId, String kind) {
        // 查询是否已有手动文档
        var existing = docRepo.findByKb(kbId).stream()
                .filter(d -> ("手工录入-" + kind).equals(d.originalFilename()))
                .findFirst();
        if (existing.isPresent()) return existing.get().id();
        Map<String, Object> meta = new HashMap<>();
        meta.put("manual", true);
        return docRepo.insert(kbId, userId, "manual-" + kind, "手工录入-" + kind,
                "", 0, "text/plain", kind, com.example.ragbot.util.JsonbUtils.toJson(meta));
    }

    public List<KbDtos.ChunkVO> listChunks(UUID userId, UUID kbId, UUID documentId) {
        kbRepo.findByIdAndUser(kbId, userId).orElseThrow(() -> new IllegalStateException("知识库不存在或无权访问"));
        List<ChunkRecordV2> chunks = documentId == null
                ? docRepo.findByKb(kbId).stream()
                    .flatMap(d -> chunkRepo.findByDocument(d.id()).stream()).toList()
                : chunkRepo.findByDocument(documentId);
        return chunks.stream().map(this::toChunkVO).toList();
    }

    public void updateChunk(UUID userId, UUID chunkId, String content, String answer) {
        var c = chunkRepo.findById(chunkId).orElseThrow(() -> new IllegalArgumentException("切片不存在"));
        kbRepo.findByIdAndUser(c.knowledgeBaseId(), userId)
                .orElseThrow(() -> new IllegalStateException("无权操作此切片"));
        chunkRepo.update(chunkId, content, answer);
        // 注意：更新文本后向量未重算。手动切片变更建议删除重建（前端提示）
    }

    public void deleteChunk(UUID userId, UUID chunkId) {
        var c = chunkRepo.findById(chunkId).orElseThrow(() -> new IllegalArgumentException("切片不存在"));
        kbRepo.findByIdAndUser(c.knowledgeBaseId(), userId)
                .orElseThrow(() -> new IllegalStateException("无权操作此切片"));
        chunkRepo.delete(chunkId);
    }

    private KbDtos.ChunkVO toChunkVO(ChunkRecordV2 c) {
        return new KbDtos.ChunkVO(c.id(), c.documentId(), c.chunkIndex(), c.chunkType(),
                c.content(), c.answer(), c.metadata(),
                c.createdAt() == null ? "" : c.createdAt().toString());
    }

    private String sanitize(String filename) {
        if (filename == null || filename.isBlank()) return "unnamed";
        return filename.replace('\\', '_').replace('/', '_').replace('\0', '_');
    }

    private String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
    }
}
