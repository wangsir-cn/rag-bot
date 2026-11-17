package com.example.ragbot.service;

import com.example.ragbot.config.RagProperties;
import com.example.ragbot.domain.RetrievedChunk;
import com.example.ragbot.dto.ChatRequestV2;
import com.example.ragbot.dto.Citation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 阶段 5 v2：Prompt 组装 + 用户模型流式生成 + 引用溯源 */
@Service
public class RagServiceV2 {

    private final RagProperties properties;
    private final ModelResolverService resolver;
    private final ModelClientFactory clientFactory;

    public RagServiceV2(RagProperties properties, ModelResolverService resolver,
                        ModelClientFactory clientFactory) {
        this.properties = properties;
        this.resolver = resolver;
        this.clientFactory = clientFactory;
    }

    public record RagContext(
            String systemPrompt,
            String userPrompt,
            List<Citation> citations,
            RetrievalServiceV2.RetrievalOutcome outcome
    ) {}

    /** 通用问答：无知识库、不检索，直接用问题作为 prompt，不产生引用 */
    public RagContext prepareGeneral(String question) {
        return new RagContext(properties.getPrompt().getGeneralSystem(), question,
                List.of(), null);
    }

    public RagContext prepare(String question, List<RetrievedChunk> chunks,
                              RetrievalServiceV2.RetrievalOutcome outcome, UUID kbId) {
        StringBuilder context = new StringBuilder();
        List<Citation> citations = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            var rc = chunks.get(i);
            var c = rc.chunk();
            String docName = String.valueOf(c.metadata().getOrDefault("docName", "未知文档"));
            context.append("[").append(i + 1).append("] 来源：").append(docName)
                    .append("（第 ").append(c.chunkIndex() + 1).append(" 段）\n");
            if ("QA".equalsIgnoreCase(c.chunkType())) {
                context.append("问：").append(c.content()).append("\n答：")
                        .append(c.answer() == null ? "" : c.answer()).append("\n\n");
            } else {
                context.append(c.content()).append("\n\n");
            }
            citations.add(new Citation(
                    i + 1, c.id().toString(), c.documentId().toString(), docName,
                    c.chunkIndex(), rc.score(), preview(c.content()),
                    c.chunkType(), c.answer(), kbId == null ? null : kbId.toString()));
        }

        String userPrompt = """
                <context>
                %s</context>

                用户问题：%s
                """.formatted(context, question);
        return new RagContext(properties.getPrompt().getSystem(), userPrompt, citations, outcome);
    }

    /** 用指定用户的 chat 模型流式生成 */
    public Flux<String> stream(RagContext context, UUID userId, List<ChatRequestV2.HistoryMessage> history) {
        ChatClient chatClient = clientFactory.chatClient(resolver.chatFor(userId));
        var spec = chatClient.prompt().system(context.systemPrompt());
        if (history != null && !history.isEmpty()) {
            List<Message> messages = new ArrayList<>();
            for (var h : history) {
                if (h == null || h.content() == null || h.content().isBlank()) continue;
                if ("assistant".equalsIgnoreCase(h.role())) messages.add(new AssistantMessage(h.content()));
                else if ("user".equalsIgnoreCase(h.role())) messages.add(new UserMessage(h.content()));
            }
            if (!messages.isEmpty()) spec.messages(messages);
        }
        return spec.user(context.userPrompt()).stream().content();
    }

    public String call(RagContext context, UUID userId) {
        ChatClient chatClient = clientFactory.chatClient(resolver.chatFor(userId));
        return chatClient.prompt().system(context.systemPrompt())
                .user(context.userPrompt()).call().content();
    }

    private String preview(String content) {
        String oneLine = content.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 160) + "…";
    }
}
