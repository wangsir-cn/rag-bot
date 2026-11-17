package com.example.ragbot.service;

import com.example.ragbot.domain.AiModelConfig;
import com.example.ragbot.domain.AppUser;
import com.example.ragbot.repository.AiModelRepository;
import com.example.ragbot.security.CurrentUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 解析用户当前生效的模型配置。
 * - 普通用户：必须已在 ai_models 绑定对应类型模型，否则抛异常（无向量模型禁止建库/上传）。
 * - 管理员：若未在页面改动（库里无配置），回退到 application.yml 的默认配置。
 */
@Service
public class ModelResolverService {

    private final AiModelRepository modelRepository;
    private final CurrentUser currentUser;

    @Value("${spring.ai.openai.base-url}") private String defaultBaseUrl;
    @Value("${spring.ai.openai.api-key}") private String defaultApiKey;
    @Value("${spring.ai.openai.embedding.options.model}") private String defaultEmbModel;
    @Value("${spring.ai.openai.embedding.options.dimensions:1024}") private int defaultDim;
    @Value("${spring.ai.openai.chat.options.model}") private String defaultChatModel;

    public ModelResolverService(AiModelRepository modelRepository, CurrentUser currentUser) {
        this.modelRepository = modelRepository;
        this.currentUser = currentUser;
    }

    /** 解析指定用户的默认向量模型；找不到时（管理员）回退配置文件 */
    public AiModelConfig embeddingFor(UUID userId) {
        return resolve(userId, "EMBEDDING");
    }

    public AiModelConfig chatFor(UUID userId) {
        return resolve(userId, "CHAT");
    }

    /** 当前登录用户的默认向量模型 */
    public AiModelConfig myEmbedding() { return embeddingFor(currentUser.id()); }

    public AiModelConfig myChat() { return chatFor(currentUser.id()); }

    private AiModelConfig resolve(UUID userId, String type) {
        return modelRepository.findDefault(userId, type)
                .orElseGet(() -> {
                    AppUser user = currentUser.get();
                    if (user.isAdmin() && user.id().equals(userId)) {
                        return configDefault(user.id(), type);
                    }
                    throw new IllegalStateException(
                            ("EMBEDDING".equals(type) ? "未配置向量模型，请先到「模型配置」添加并设为默认" :
                             "未配置生成模型，请先到「模型配置」添加并设为默认"));
                });
    }

    /** 由 application.yml 生成的虚拟默认配置（管理员未改动时使用，不入库） */
    private AiModelConfig configDefault(UUID userId, String type) {
        boolean emb = "EMBEDDING".equals(type);
        return new AiModelConfig(
                UUID.nameUUIDFromBytes(("config-default-" + type).getBytes()), // 稳定虚拟 ID
                userId,
                emb ? "默认向量模型(配置文件)" : "默认生成模型(配置文件)",
                type,
                defaultBaseUrl,
                defaultApiKey,
                emb ? defaultEmbModel : defaultChatModel,
                emb ? defaultDim : null,
                true,
                null
        );
    }
}
