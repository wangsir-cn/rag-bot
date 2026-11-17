package com.example.ragbot.controller;

import com.example.ragbot.domain.ChatSession;
import com.example.ragbot.dto.ChatRequestV2;
import com.example.ragbot.dto.Citation;
import com.example.ragbot.dto.RetrievalDebugResponse;
import com.example.ragbot.security.CurrentUser;
import com.example.ragbot.service.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.*;

@RestController
@RequestMapping("/api/chat")
public class ChatControllerV2 {

    private static final Logger log = LoggerFactory.getLogger(ChatControllerV2.class);

    private final RetrievalServiceV2 retrievalService;
    private final RagServiceV2 ragService;
    private final ChatSessionService sessionService;
    private final CurrentUser currentUser;
    private final ObjectMapper objectMapper;

    public ChatControllerV2(RetrievalServiceV2 retrievalService, RagServiceV2 ragService,
                            ChatSessionService sessionService, CurrentUser currentUser,
                            ObjectMapper objectMapper) {
        this.retrievalService = retrievalService;
        this.ragService = ragService;
        this.sessionService = sessionService;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    /** SSE: references -> delta... -> done / error */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> stream(@RequestBody ChatRequestV2 req) {
        String question = req.question() == null ? "" : req.question().trim();
        if (question.isBlank()) return Flux.just(event("error", json(Map.of("error", "问题不能为空"))));
        UUID userId = currentUser.id();
        UUID kbId = req.knowledgeBaseId();
        boolean generalChat = (kbId == null);

        try {
            // 解析/创建会话（kbId 为空表示通用问答会话）
            ChatSession session = sessionService.resolveSession(userId, req.sessionId(), kbId);
            UUID sessionId = session.id();
            // 首次对话自动命名
            if ("新对话".equals(session.title())) sessionService.autoTitle(sessionId, question);

            // 重建多轮历史（新问题入库之前的历史）
            List<ChatRequestV2.HistoryMessage> history = sessionService.history(sessionId, 20);

            // 有知识库走 RAG 检索，无知识库走通用问答
            RagServiceV2.RagContext context;
            RetrievalServiceV2.RetrievalOutcome outcome = null;
            if (generalChat) {
                context = ragService.prepareGeneral(question);
            } else {
                outcome = retrievalService.retrieve(question, kbId, req.options());
                context = ragService.prepare(question, outcome.chunks(), outcome, kbId);
            }

            StringBuilder fullAnswer = new StringBuilder();
            Flux<ServerSentEvent<String>> deltas = ragService.stream(context, userId, history)
                    .doOnNext(fullAnswer::append)
                    .map(token -> event("delta", json(token)));

            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sessionId", sessionId.toString());
            meta.put("mode", generalChat ? "GENERAL" : "RAG");
            meta.put("citationCount", context.citations().size());
            meta.put("vectorCount", outcome == null ? 0 : outcome.vectorCount());
            meta.put("bm25Count", outcome == null ? 0 : outcome.bm25Count());
            meta.put("totalMs", outcome == null ? 0 : outcome.totalMs());
            meta.put("applied", outcome == null ? Map.of() : outcome.applied());

            // 生成完成后保存消息（用户问题 + AI 回答 + 引用）
            Flux<ServerSentEvent<String>> saved = Flux.defer(() -> {
                sessionService.saveUserMessage(sessionId, question);
                sessionService.saveAssistantMessage(sessionId, fullAnswer.toString(), citationsJson(context.citations()));
                return Flux.just(event("done", json(meta)));
            });

            return Flux.concat(
                    Flux.just(event("references", json(context.citations()))),
                    deltas,
                    saved
            ).onErrorResume(e -> {
                log.error("RAG 流式生成失败 question={}", question, e);
                return Flux.just(event("error", json(Map.of("error",
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))));
            });
        } catch (Exception e) {
            log.error("RAG 请求处理失败 question={}", question, e);
            return Flux.just(event("error", json(Map.of("error",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()))));
        }
    }

    /** 检索调试：仅召回，不调用 LLM */
    @PostMapping("/retrieve")
    public RetrievalDebugResponse retrieve(@RequestBody ChatRequestV2 req) {
        if (req.knowledgeBaseId() == null) throw new IllegalArgumentException("请先选择知识库");
        var outcome = retrievalService.retrieve(
                req.question() == null ? "" : req.question(), req.knowledgeBaseId(), req.options());
        List<RetrievalDebugResponse.ChunkHit> hits = outcome.chunks().stream().map(rc -> {
            var c = rc.chunk();
            return new RetrievalDebugResponse.ChunkHit(
                    c.id().toString(), c.documentId().toString(),
                    String.valueOf(c.metadata().getOrDefault("docName", "未知文档")),
                    c.chunkIndex(), rc.score(), c.content(), c.metadata(),
                    rc.vectorRank(), rc.bm25Rank());
        }).toList();
        return new RetrievalDebugResponse(hits, outcome.vectorCount(), outcome.bm25Count(),
                outcome.fusion(), outcome.vectorMs(), outcome.bm25Ms(), outcome.totalMs(),
                outcome.applied());
    }

    // ===== 会话管理 =====
    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() { return sessionService.listSessions(currentUser.id()); }

    @PostMapping("/sessions")
    public Map<String, Object> createSession(@RequestBody Map<String, Object> body) {
        Object raw = body.get("knowledgeBaseId");
        UUID kbId = (raw == null || String.valueOf(raw).isBlank())
                ? null : UUID.fromString(String.valueOf(raw));
        return Map.of("id", sessionService.createSession(currentUser.id(), kbId).toString());
    }

    @GetMapping("/sessions/{sessionId}")
    public Map<String, Object> sessionDetail(@PathVariable UUID sessionId) {
        return sessionService.getMessages(currentUser.id(), sessionId);
    }

    @DeleteMapping("/sessions/{sessionId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable UUID sessionId) {
        sessionService.deleteSession(currentUser.id(), sessionId);
    }

    private String citationsJson(List<Citation> citations) {
        try { return objectMapper.writeValueAsString(citations); }
        catch (JsonProcessingException e) { return "[]"; }
    }

    private ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { return "{\"error\":\"序列化失败\"}"; }
    }
}
