package com.example.ragbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatRequestV2(
        String question,
        UUID knowledgeBaseId,     // 可选：为空则走通用问答（不检索知识库）
        UUID sessionId,           // 可选：续聊已有会话；为空则新建
        List<HistoryMessage> history,
        RetrievalOptions options   // 可选：仅 /retrieve 调试接口使用，覆盖全局检索参数
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HistoryMessage(String role, String content) {}
}
