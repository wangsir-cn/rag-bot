package com.example.ragbot.controller;

import com.example.ragbot.domain.AiModelConfig;
import com.example.ragbot.repository.AiModelRepository;
import com.example.ragbot.security.CurrentUser;
import com.example.ragbot.service.ModelClientFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/models")
public class ModelController {

    private final AiModelRepository repo;
    private final CurrentUser currentUser;
    private final ModelClientFactory clientFactory;

    public ModelController(AiModelRepository repo, CurrentUser currentUser, ModelClientFactory clientFactory) {
        this.repo = repo;
        this.currentUser = currentUser;
        this.clientFactory = clientFactory;
    }

    public record ModelRequest(String name, String modelType, String baseUrl, String apiKey,
                               String modelName, Integer dimensions, boolean isDefault) {}

    @GetMapping
    public List<Map<String, Object>> list() {
        return repo.findByUser(currentUser.id()).stream().map(this::view).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestBody ModelRequest req) {
        validate(req);
        UUID id = repo.insert(currentUser.id(), req.name(), req.modelType(), req.baseUrl(),
                req.apiKey(), req.modelName(), req.dimensions(), req.isDefault());
        clientFactory.clearCache();
        return view(repo.findById(id).orElseThrow());
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody ModelRequest req) {
        var existing = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("模型不存在"));
        if (!existing.userId().equals(currentUser.id())) throw new IllegalStateException("无权修改此模型");
        // apiKey 留空表示不修改（前端不回传明文密钥）
        String apiKey = (req.apiKey() == null || req.apiKey().isBlank()) ? existing.apiKey() : req.apiKey();
        ModelRequest effective = new ModelRequest(req.name(), req.modelType(), req.baseUrl(), apiKey,
                req.modelName(), req.dimensions(), req.isDefault());
        validate(effective);
        repo.update(id, effective.name(), effective.modelType(), effective.baseUrl(), apiKey,
                effective.modelName(), effective.dimensions(), effective.isDefault());
        clientFactory.clearCache();
        return view(repo.findById(id).orElseThrow());
    }

    @PostMapping("/{id}/default")
    public Map<String, Object> setDefault(@PathVariable UUID id) {
        var m = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("模型不存在"));
        if (!m.userId().equals(currentUser.id())) throw new IllegalStateException("无权操作此模型");
        repo.setDefault(id, currentUser.id(), m.modelType());
        clientFactory.clearCache();
        return Map.of("success", true);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        var m = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("模型不存在"));
        if (!m.userId().equals(currentUser.id())) throw new IllegalStateException("无权删除此模型");
        // 被知识库引用则不允许删除
        Long used = repo.countKbUsage(id);
        if (used != null && used > 0) throw new IllegalStateException("该模型已被知识库引用，无法删除");
        repo.delete(id);
        clientFactory.clearCache();
    }

    /** 测试模型连通性 */
    @PostMapping("/{id}/test")
    public Map<String, Object> test(@PathVariable UUID id) {
        var m = repo.findById(id).orElseThrow(() -> new IllegalArgumentException("模型不存在"));
        if (!m.userId().equals(currentUser.id())) throw new IllegalStateException("无权操作此模型");
        boolean ok;
        try {
            if (m.isEmbedding()) {
                var v = clientFactory.embeddingModel(m).embed("test");
                ok = v != null && v.length > 0;
            } else {
                String r = clientFactory.chatClient(m).prompt().user("回复OK").call().content();
                ok = r != null && !r.isBlank();
            }
        } catch (Exception e) {
            throw new IllegalStateException("连接失败: " + e.getMessage());
        }
        return Map.of("success", ok);
    }

    private void validate(ModelRequest req) {
        if (req.name() == null || req.name().isBlank()) throw new IllegalArgumentException("名称不能为空");
        if (!"CHAT".equals(req.modelType()) && !"EMBEDDING".equals(req.modelType())) {
            throw new IllegalArgumentException("模型类型必须是 CHAT 或 EMBEDDING");
        }
        if (req.baseUrl() == null || req.baseUrl().isBlank()) throw new IllegalArgumentException("Base URL 不能为空");
        if (req.baseUrl().trim().endsWith("/v1")) {
            throw new IllegalArgumentException("Base URL 不要以 /v1 结尾，Spring AI 会自动拼接 /v1/...");
        }
        if (req.apiKey() == null || req.apiKey().isBlank()) throw new IllegalArgumentException("API Key 不能为空");
        if (req.modelName() == null || req.modelName().isBlank()) throw new IllegalArgumentException("模型名称不能为空");
        if ("EMBEDDING".equals(req.modelType()) && (req.dimensions() == null || req.dimensions() != 1024)) {
            throw new IllegalArgumentException("向量维度必须为 1024（与数据库列一致）");
        }
    }

    private Map<String, Object> view(AiModelConfig m) {
        String maskedKey = m.apiKey().length() > 8
                ? m.apiKey().substring(0, 4) + "****" + m.apiKey().substring(m.apiKey().length() - 4)
                : "****";
        return Map.of(
                "id", m.id().toString(), "name", m.name(), "modelType", m.modelType(),
                "baseUrl", m.baseUrl(), "apiKeyMasked", maskedKey, "modelName", m.modelName(),
                "dimensions", m.dimensions() == null ? 0 : m.dimensions(),
                "isDefault", m.isDefault()
        );
    }
}
