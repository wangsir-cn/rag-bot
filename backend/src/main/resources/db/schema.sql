-- =============================================================================
-- RAG Bot v2 schema - PostgreSQL 15+ / pgvector 0.5+
-- 用户体系 / 用户维度模型 / 知识库 / 文档 / 切片 / 会话历史
--
-- 注意：本文件使用 CREATE ... IF NOT EXISTS，可安全重复执行。
-- 向量维度固定为 1024（HNSW 索引要求列维度固定），所有 Embedding 模型
-- 必须声明相同 dimensions；修改维度须同步改所有 vector(1024) 并重建表。
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid()

-- -----------------------------------------------------------------------------
-- 1. 用户与认证
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username              VARCHAR(64)  NOT NULL UNIQUE,
    email                 VARCHAR(255) NOT NULL UNIQUE,
    password_hash         VARCHAR(255) NOT NULL,
    role                  VARCHAR(16)  NOT NULL DEFAULT 'USER',   -- ADMIN / USER
    must_change_password  BOOLEAN NOT NULL DEFAULT FALSE,
    enabled               BOOLEAN NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    token      VARCHAR(128) PRIMARY KEY,       -- SHA-256(token) 存储，不明文
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_prt_user ON password_reset_tokens(user_id);

-- 管理员默认模型配置存库（页面改过之后写这里，未改时用 application.yml 默认值）
CREATE TABLE IF NOT EXISTS app_settings (
    key        VARCHAR(128) PRIMARY KEY,
    value      JSONB NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- 2. AI 模型配置（按用户绑定；生成模型与向量模型类型分离）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ai_models (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(128) NOT NULL,                 -- 展示别名
    model_type  VARCHAR(16)  NOT NULL,                 -- CHAT / EMBEDDING
    base_url    VARCHAR(512) NOT NULL,
    api_key     VARCHAR(512) NOT NULL,
    model_name  VARCHAR(128) NOT NULL,                 -- 实际调用的模型 ID
    dimensions  INTEGER,                               -- EMBEDDING 必填，必须 = 1024
    is_default  BOOLEAN NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ai_model_name UNIQUE (user_id, name)
);
CREATE INDEX IF NOT EXISTS idx_ai_models_user_type ON ai_models(user_id, model_type);
-- 每个用户每种类型最多一个 default
CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_model_default
    ON ai_models(user_id, model_type) WHERE is_default;

-- -----------------------------------------------------------------------------
-- 3. 知识库（用户维度，必须绑定一个 EMBEDDING 模型）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name              VARCHAR(128) NOT NULL,
    description       TEXT,
    embedding_model_id UUID NOT NULL REFERENCES ai_models(id),
    chunk_strategy    VARCHAR(16) NOT NULL DEFAULT 'SEMANTIC',  -- FIXED / SEMANTIC
    chunk_size        INTEGER NOT NULL DEFAULT 800,
    chunk_overlap     INTEGER NOT NULL DEFAULT 120,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_kb_name UNIQUE (user_id, name)
);
CREATE INDEX IF NOT EXISTS idx_kb_user ON knowledge_bases(user_id);

-- -----------------------------------------------------------------------------
-- 4. 文档（归属知识库）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS documents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    filename          VARCHAR(512) NOT NULL,          -- 存储文件名
    original_filename VARCHAR(512) NOT NULL,
    file_path         TEXT NOT NULL,
    file_size         BIGINT NOT NULL DEFAULT 0,
    mime_type         VARCHAR(255),
    file_kind         VARCHAR(16) NOT NULL,           -- TEXT / QA
    status            VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    error_message     TEXT,
    chunk_count       INTEGER NOT NULL DEFAULT 0,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_docs_kb ON documents(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_docs_user ON documents(user_id);
CREATE INDEX IF NOT EXISTS idx_docs_status ON documents(status);

-- -----------------------------------------------------------------------------
-- 5. 切片（TEXT 文本切片 / QA 问答切片）
--    content: TEXT=正文, QA=问题; answer: QA=答案, TEXT=NULL
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chunks (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id       UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    knowledge_base_id UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    chunk_index       INTEGER NOT NULL,
    chunk_type        VARCHAR(16) NOT NULL,           -- TEXT / QA
    content           TEXT NOT NULL,
    answer            TEXT,
    search_text       TEXT NOT NULL DEFAULT '',
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding         vector(1024) NOT NULL,
    content_tsv       tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(search_text, ''))
    ) STORED,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chunks_doc_index UNIQUE (document_id, chunk_index)
);
CREATE INDEX IF NOT EXISTS idx_chunks_kb ON chunks(knowledge_base_id);
CREATE INDEX IF NOT EXISTS idx_chunks_doc ON chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_chunks_content_tsv ON chunks USING gin (content_tsv);
CREATE INDEX IF NOT EXISTS idx_chunks_metadata ON chunks USING gin (metadata jsonb_path_ops);
CREATE INDEX IF NOT EXISTS idx_chunks_embedding_hnsw
    ON chunks USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- -----------------------------------------------------------------------------
-- 6. 问答会话与消息（用户维度，多轮续聊）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS chat_sessions (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    knowledge_base_id UUID REFERENCES knowledge_bases(id) ON DELETE SET NULL,
    title             VARCHAR(255) NOT NULL DEFAULT '新对话',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_sessions_user ON chat_sessions(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS chat_messages (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL REFERENCES chat_sessions(id) ON DELETE CASCADE,
    role       VARCHAR(16) NOT NULL,                  -- USER / ASSISTANT
    content    TEXT NOT NULL,
    citations  JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_messages_session ON chat_messages(session_id, created_at);

-- -----------------------------------------------------------------------------
-- 7. 语义缓存（按知识库隔离，避免跨库串味）
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS query_cache (
    cache_key  VARCHAR(64) PRIMARY KEY,
    kb_id      UUID NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    query      TEXT NOT NULL,
    embedding  vector(1024) NOT NULL,
    answer     TEXT NOT NULL,
    citations  JSONB NOT NULL DEFAULT '[]'::jsonb,
    hit_count  INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + interval '24 hours')
);
CREATE INDEX IF NOT EXISTS idx_query_cache_kb ON query_cache(kb_id);
CREATE INDEX IF NOT EXISTS idx_query_cache_embedding_hnsw
    ON query_cache USING hnsw (embedding vector_cosine_ops);
