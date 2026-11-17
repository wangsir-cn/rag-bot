# RAG Bot v2 —— 多用户企业知识库问答系统

基于 **Spring Boot 3.5.16 + Spring AI 1.1.8 + PostgreSQL(pgvector) + Vue 3 / Element Plus** 的完整 RAG 系统。相比 v1，v2 补齐了**多用户认证、按用户维度的多模型配置、知识库层级、问答历史、两类文档（文本/QA）与切片管理**。

> 本次重构按需求**丢弃了旧数据并重建全部表结构**（`backend/src/main/resources/db/schema.sql`）。

---

## 0. 版本与栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 3.5.16 | 你要求的 3.x 系列 |
| Spring AI | 1.1.8 | 兼容 Boot 3.x 的最新稳定线（2.x 需 Boot 4） |
| Java | 21 | - |
| PostgreSQL / pgvector | 实测 18.7，兼容 15+ | HNSW 索引需 pgvector ≥ 0.5 |
| Vue / Vite / Element Plus | 3.5 / 6 / 2.9 | Composition API + `<script setup>` |
| JWT | jjwt 0.12.6 | 无状态认证 |
| JavaMail | Spring Boot Mail Starter | 密码重置邮件 |

---

## 1. 核心能力（v2 新增）

1. **认证体系**：默认管理员 `admin/admin`，**首次登录强制改密**；其他用户需注册（用户名 + 邮箱 + 密码）；邮箱用于**忘记密码重置**（SMTP 发信，未配置时链接打印到后端日志）。
2. **按用户维度的模型配置**：生成模型（CHAT）与向量模型（EMBEDDING）**完全分离**，各自可设 URL / API Key / 模型名；**无向量模型不能创建知识库、不能上传文件**。管理员首次启动时自动把配置文件默认模型入库，之后页面修改即存库。
3. **知识库层级**：用户可建多个知识库，每个库**必须绑定一个向量模型**，并可配分块策略/大小/重叠。
4. **两类文档**：
   - **文本文件**（txt/md/doc/docx/pdf）：Tika 解析 → 可配置分块策略（固定长度带重叠 / 语义段落句子）。
   - **QA 文件**（csv/tsv）：每行「问题,答案」= 1 个 QA 切片；提供模板下载。
   - 一个知识库内 **TEXT 与 QA 切片互斥**（不允许混用）。
5. **切片管理**：文档列表的切片数可点击跳转；支持**手动创建** TEXT / QA 切片、编辑、删除。
6. **问答历史**：左侧会话列表，点开历史**继续多轮对话**；每个会话绑定知识库。
7. **知识库绑定召回**：问答与检索**必须先选知识库**，只用该库的数据召回；引用片段支持**跳转到知识库对应切片**并高亮。

---

## 2. 系统架构图

```mermaid
flowchart TB
    subgraph FE[前端 Vue 3]
        Login[登录/注册/改密/找回密码]
        Chat[ChatView<br/>会话列表+知识库选择+流式问答]
        KB[KnowledgeView<br/>知识库/文档/切片]
        Model[ModelView<br/>CHAT/EMBEDDING 配置]
        Debug[DebugView<br/>检索调试]
    end

    subgraph API[API 层 + Spring Security/JWT]
        Auth[AuthController]
        KBC[KnowledgeBaseController]
        DocC[DocumentControllerV2]
        ModelC[ModelController]
        ChatC[ChatControllerV2<br/>SSE]
    end

    subgraph SVC[服务层]
        MRF[ModelClientFactory<br/>按配置动态构建模型客户端]
        Ing[IngestionWorkerV2<br/>解析→分块→向量化]
        Retr[RetrievalServiceV2<br/>向量+BM25+RRF]
        Rag[RagServiceV2<br/>Prompt+流式生成+引用]
    end

    subgraph DB[(PostgreSQL + pgvector)]
        Users[users / ai_models]
        KBDB[knowledge_bases / documents]
        Chunks[chunks vector(1024) + tsvector]
        Sessions[chat_sessions / chat_messages]
    end

    LLM[OpenAI 兼容 LLM / Embedding<br/>每个用户可不同服务商]

    Login --> Auth --> Users
    Model --> ModelC --> MRF
    KB --> KBC --> KBDB
    KB --> DocC --> Ing --> Chunks
    Chat --> ChatC --> Retr --> Chunks
    ChatC --> Rag --> LLM
    Retr --> MRF --> LLM
    MRF --> Users
    Debug --> ChatC
```

---

## 3. 数据库设计（完整 DDL 见 `backend/src/main/resources/db/schema.sql`）

| 表 | 用途 | 关键字段 |
|----|------|---------|
| `users` | 用户 | username/email/password_hash/role/must_change_password |
| `password_reset_tokens` | 密码重置令牌 | token(SHA256)/expires_at/used |
| `app_settings` | 管理员默认配置存库 | key/value(jsonb) |
| `ai_models` | 用户模型配置 | user_id/model_type(CHAT\|EMBEDDING)/base_url/api_key/model_name/dimensions/is_default |
| `knowledge_bases` | 知识库 | user_id/embedding_model_id/chunk_strategy/size/overlap |
| `documents` | 文档 | knowledge_base_id/file_kind(TEXT\|QA)/status/chunk_count |
| `chunks` | 切片 | chunk_type(TEXT\|QA)/content/answer/embedding vector(1024)/content_tsv |
| `chat_sessions` | 问答会话 | user_id/knowledge_base_id/title |
| `chat_messages` | 会话消息 | session_id/role/content/citations(jsonb) |
| `query_cache` | 语义缓存 | kb_id/embedding/answer |

> [!NOTE] **为什么向量维度固定 1024？**
> pgvector 的 HNSW 索引要求列维度固定，不能每行不同。因此系统统一用 `vector(1024)`，所有用户绑定的向量模型必须输出 1024 维（模型配置保存时会校验）。更换维度需同步改所有 `vector(1024)` 并重建表。管理员/用户可换**不同服务商**的模型，只要维度一致。

> [!NOTE] **为什么每个用户每种模型只能有一个默认（局部唯一索引）？**
> `CREATE UNIQUE INDEX uq_ai_model_default ON ai_models(user_id, model_type) WHERE is_default` 保证「每个用户的每种类型恰好一个默认」，避免解析默认模型时出现歧义。

---

## 4. 关键设计决策

> [!NOTE] **动态模型客户端（ModelClientFactory）**
> v1 的 ChatClient/EmbeddingModel 由 application.yml 全局注入，无法按用户切换。v2 用 `OpenAiApi.builder().baseUrl(...).apiKey(...)` 在运行时按用户配置构建客户端，并以「配置指纹」为 key 做缓存；模型配置变更后清空缓存自动重建。这样两个用户可以各用不同厂商的模型。

> [!NOTE] **base-url 不要带 `/v1`**
> Spring AI 会自动拼接 `/v1/embeddings`、`/v1/chat/completions`。若 base-url 写成 `.../compatible-mode/v1`，实际请求会变成 `/v1/v1/embeddings` → 404。模型配置保存时会拒绝以 `/v1` 结尾的地址。

> [!NOTE] **BM25 中文分词**
> 标准 pgvector 镜像无中文分词插件，写入时把中文按二元切分（bigram）展开进 `search_text`，查询时同样处理，用 `to_tsquery('simple', OR 表达式)` 召回，无需 zhparser。SQL 用 `CROSS JOIN (SELECT to_tsquery('simple', ?) AS query) q` 显式命名列（直接写 `to_tsquery(...) q` 会导致 `q.query does not exist`）。

> [!NOTE] **多轮对话历史**
> 消息持久化在 `chat_messages`，续聊时用 `ChatSessionService.history()` 从库里重建最近 20 条作为 ChatClient 的 history，因此刷新页面/重开会话都能继续多轮。

---

## 5. 配置与启动

配置文件：`backend/src/main/resources/application.yml`；环境变量模板见 `.env.example`。

关键新增项：
- `security.jwt.secret` / `expiration-hours`：JWT 密钥与有效期（生产必须改）
- `security.default-admin.*`：默认管理员账号（首次登录强制改密）
- `spring.mail.*` + `security.reset-password-url`：密码重置邮件
- `OPENAI_BASE_URL` / `EMBEDDING_DIMENSIONS`：默认模型（admin 初始化入库）

启动：
```bash
cp .env.example .env      # 按需修改
docker compose up -d --build
# 前端 http://localhost:5173  后端 http://localhost:8090
```
首次用 `admin / admin` 登录 → 强制改密 → 到「模型配置」确认/修改模型 → 到「知识库管理」新建知识库（绑定向量模型）→ 上传文本或 QA 文件 → 到「智能问答」选择知识库提问。

---

## 6. API 一览

| 模块 | 端点 |
|------|------|
| 认证 | `POST /api/auth/login` `/register` `/change-password` `/forgot-password` `/reset-password`，`GET /me` |
| 模型 | `GET/POST/PUT/DELETE /api/models`，`POST /{id}/default` `/{id}/test` |
| 知识库 | `GET/POST/PUT/DELETE /api/kb` |
| 文档 | `GET/POST/DELETE /api/kb/{kbId}/documents`，`POST /{docId}/reprocess` |
| 切片 | `GET/POST/PUT/DELETE /api/kb/{kbId}/documents/chunks` |
| 模板 | `GET /api/templates/qa` |
| 问答 | `POST /api/chat/stream`(SSE)，`POST /api/chat/retrieve`，`GET/POST/DELETE /api/chat/sessions` |

SSE 事件：`references`（引用卡片）→ `delta`（逐 token）→ `done`（含 sessionId/耗时）/ `error`。

---

## 7. 最佳实践（沿用 v1）

- **Chunk**：默认 800 字符 / overlap 120，`chunk size < 上下文窗口 1/10`；语义分块优先，用评测集调参。
- **Embedding 选型**：中文优先中文强化模型（qwen/bge-m3），同一知识库内所有 chunk 与 query 必须同模型；换模型需全量重嵌。
- **Rerank**：可选 Cross-Encoder（`/v1/rerank` 兼容接口），失败自动降级为原融合结果。
- **语义缓存**：`rag.cache.enabled=true` 开启，按 kb 隔离，相似度 ≥0.97 命中。
- **评测指标**：Faithfulness（忠实度）、Answer Relevance、Context Recall、MRR/NDCG（检索质量）。

---

## 8. 验证状态

- 后端：`mvn -o clean compile` **BUILD SUCCESS**（65 个 Java 源文件）
- 前端：`npm run build` **通过**（12 个 Vue 文件， lazy-loaded 路由）
- 数据库：新 schema 已在实测环境（PostgreSQL 18.7）完整执行，10 张表创建成功；旧数据已按需求清空
- 说明：本次为工程实现，未做浏览器端 E2E 交互测试；首次启动请按第 5 节流程走通一遍。
