package com.example.ragbot.service;

import com.example.ragbot.domain.ChatSession;
import com.example.ragbot.repository.ChatSessionRepository;
import com.example.ragbot.repository.KnowledgeBaseRepository;
import com.example.ragbot.util.JsonbUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatSessionService {

    private final ChatSessionRepository repo;
    private final KnowledgeBaseRepository kbRepo;

    public ChatSessionService(ChatSessionRepository repo, KnowledgeBaseRepository kbRepo) {
        this.repo = repo;
        this.kbRepo = kbRepo;
    }

    public List<Map<String, Object>> listSessions(UUID userId) {
        return repo.findByUser(userId).stream().map(s -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", s.id().toString());
            m.put("title", s.title());
            m.put("knowledgeBaseId", s.knowledgeBaseId() == null ? null : s.knowledgeBaseId().toString());
            String kbName = s.knowledgeBaseId() == null ? null
                    : kbRepo.findById(s.knowledgeBaseId()).map(k -> k.name()).orElse("已删除");
            m.put("knowledgeBaseName", kbName);
            m.put("updatedAt", s.updatedAt() == null ? "" : s.updatedAt().toString());
            return m;
        }).toList();
    }

    public UUID createSession(UUID userId, UUID kbId) {
        if (kbId != null) {
            kbRepo.findByIdAndUser(kbId, userId).orElseThrow(() -> new IllegalStateException("知识库不存在或无权访问"));
        }
        return repo.insert(userId, kbId, "新对话");
    }

    /** 续聊：校验会话归属；若切换知识库（含切到通用问答）则更新绑定 */
    public ChatSession resolveSession(UUID userId, UUID sessionId, UUID kbId) {
        if (kbId != null) {
            kbRepo.findByIdAndUser(kbId, userId).orElseThrow(() -> new IllegalStateException("知识库不存在或无权访问"));
        }
        if (sessionId != null) {
            var s = repo.findByIdAndUser(sessionId, userId)
                    .orElseThrow(() -> new IllegalStateException("会话不存在或无权访问"));
            if (!java.util.Objects.equals(kbId, s.knowledgeBaseId())) repo.updateKb(sessionId, kbId);
            return s;
        }
        UUID id = repo.insert(userId, kbId, "新对话");
        return repo.findByIdAndUser(id, userId).orElseThrow();
    }

    public void autoTitle(UUID sessionId, String question) {
        String title = question.replaceAll("\\s+", " ").trim();
        if (title.length() > 30) title = title.substring(0, 30) + "…";
        repo.updateTitle(sessionId, title);
    }

    public Map<String, Object> getMessages(UUID userId, UUID sessionId) {
        var s = repo.findByIdAndUser(sessionId, userId)
                .orElseThrow(() -> new IllegalStateException("会话不存在或无权访问"));
        List<Map<String, Object>> messages = repo.findMessages(sessionId).stream().map(m -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", m.id().toString());
            item.put("role", m.role());
            item.put("content", m.content());
            Object citations;
            try {
                citations = new com.fasterxml.jackson.databind.ObjectMapper().readValue(m.citations(), Object.class);
            } catch (Exception e) {
                citations = List.of();
            }
            item.put("citations", citations);
            return item;
        }).toList();
        Map<String, Object> sessionInfo = new java.util.LinkedHashMap<>();
        sessionInfo.put("id", s.id().toString());
        sessionInfo.put("title", s.title());
        sessionInfo.put("knowledgeBaseId", s.knowledgeBaseId() == null ? null : s.knowledgeBaseId().toString());
        return Map.of("session", sessionInfo, "messages", messages);
    }

    public void deleteSession(UUID userId, UUID sessionId) {
        repo.findByIdAndUser(sessionId, userId)
                .orElseThrow(() -> new IllegalStateException("会话不存在或无权访问"));
        repo.delete(sessionId);
    }

    public void saveUserMessage(UUID sessionId, String question) {
        repo.insertMessage(sessionId, "USER", question, "[]");
    }

    public void saveAssistantMessage(UUID sessionId, String answer, String citationsJson) {
        repo.insertMessage(sessionId, "ASSISTANT", answer, citationsJson);
    }

    /** 从持久化消息重建多轮历史（最近 N 条） */
    public List<com.example.ragbot.dto.ChatRequestV2.HistoryMessage> history(UUID sessionId, int limit) {
        var msgs = repo.findMessages(sessionId);
        int from = Math.max(0, msgs.size() - limit);
        return msgs.subList(from, msgs.size()).stream()
                .map(m -> new com.example.ragbot.dto.ChatRequestV2.HistoryMessage(m.role(), m.content()))
                .toList();
    }
}
