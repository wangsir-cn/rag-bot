package com.example.ragbot.controller;

import com.example.ragbot.dto.KbDtos;
import com.example.ragbot.security.CurrentUser;
import com.example.ragbot.service.DocumentServiceV2;
import com.example.ragbot.service.QaParsingService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/kb/{kbId}/documents")
public class DocumentControllerV2 {

    private final DocumentServiceV2 service;
    private final QaParsingService qaParsing;
    private final CurrentUser currentUser;

    public DocumentControllerV2(DocumentServiceV2 service, QaParsingService qaParsing, CurrentUser currentUser) {
        this.service = service;
        this.qaParsing = qaParsing;
        this.currentUser = currentUser;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> upload(@PathVariable UUID kbId,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "fileKind", required = false) String fileKind) throws IOException {
        var result = service.upload(currentUser.id(), kbId, file, fileKind);
        return Map.of("id", result.documentId() == null ? "" : result.documentId().toString(),
                "message", "上传成功，正在后台解析");
    }

    @GetMapping
    public List<Map<String, Object>> list(@PathVariable UUID kbId) {
        return service.listDocs(currentUser.id(), kbId);
    }

    @DeleteMapping("/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID kbId, @PathVariable UUID docId) throws IOException {
        service.deleteDoc(currentUser.id(), docId);
    }

    @PostMapping("/{docId}/reprocess")
    public Map<String, Object> reprocess(@PathVariable UUID kbId, @PathVariable UUID docId) {
        service.reprocess(currentUser.id(), docId);
        return Map.of("success", true);
    }

    // ===== 切片管理 =====

    @GetMapping("/chunks")
    public List<KbDtos.ChunkVO> listChunks(@PathVariable UUID kbId,
                                            @RequestParam(value = "documentId", required = false) UUID documentId) {
        return service.listChunks(currentUser.id(), kbId, documentId);
    }

    @PostMapping("/chunks")
    public KbDtos.ChunkVO createChunk(@PathVariable UUID kbId,
                                       @RequestParam String chunkType,
                                       @RequestParam String content,
                                       @RequestParam(required = false) String answer) {
        return service.createChunk(currentUser.id(), kbId, chunkType, content, answer);
    }

    @PutMapping("/chunks/{chunkId}")
    public Map<String, Object> updateChunk(@PathVariable UUID kbId, @PathVariable UUID chunkId,
                                            @RequestParam String content,
                                            @RequestParam(required = false) String answer) {
        service.updateChunk(currentUser.id(), chunkId, content, answer);
        return Map.of("success", true);
    }

    @DeleteMapping("/chunks/{chunkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChunk(@PathVariable UUID kbId, @PathVariable UUID chunkId) {
        service.deleteChunk(currentUser.id(), chunkId);
    }

    /** 下载 QA 模板 */
    @GetMapping("/qa-template")
    public ResponseEntity<byte[]> qaTemplate() {
        byte[] bytes = qaParsing.template().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=qa_template.csv")
                .body(bytes);
    }
}
