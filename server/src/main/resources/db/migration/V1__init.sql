-- 面试陪练 · 初始 schema(pgvector)
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS doc (
    id         BIGSERIAL PRIMARY KEY,
    group_tag  TEXT NOT NULL,                 -- 板块:java/database/architecture/tools
    slug       TEXT NOT NULL,                 -- 文件名(去 .md)
    title      TEXT NOT NULL,
    url        TEXT,
    lastmod    DATE,
    source     TEXT NOT NULL DEFAULT 'xiaolincoding',
    md_file    TEXT NOT NULL,
    UNIQUE (group_tag, slug)
);

CREATE TABLE IF NOT EXISTS chunk (
    id          BIGSERIAL PRIMARY KEY,
    doc_id      BIGINT NOT NULL REFERENCES doc (id) ON DELETE CASCADE,
    category    TEXT,                          -- 父标题(类目),无则 NULL
    question    TEXT NOT NULL,
    question_key TEXT NOT NULL,                -- 归一化题干,供精确/关键词命中
    content     TEXT NOT NULL,                 -- 该题完整答案(markdown)
    embedding   vector(1024),                  -- text-embedding-v4,维度固定 1024
    UNIQUE (doc_id, question)
);
CREATE INDEX IF NOT EXISTS idx_chunk_doc         ON chunk (doc_id);
CREATE INDEX IF NOT EXISTS idx_chunk_question_key ON chunk (question_key);
-- HNSW 余弦索引;数据量小时 HNSW 比 ivfflat 更稳
CREATE INDEX IF NOT EXISTS idx_chunk_embedding
    ON chunk USING hnsw (embedding vector_cosine_ops);

CREATE TABLE IF NOT EXISTS quiz_session (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_tag  TEXT,                           -- 抽题范围,空=全库
    status     TEXT NOT NULL DEFAULT 'active',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS quiz_answer (
    id          BIGSERIAL PRIMARY KEY,
    session_id  UUID NOT NULL REFERENCES quiz_session (id) ON DELETE CASCADE,
    chunk_id    BIGINT REFERENCES chunk (id) ON DELETE SET NULL,
    group_tag   TEXT,
    category    TEXT,
    question    TEXT NOT NULL,
    user_answer TEXT NOT NULL,
    grade       JSONB,                         -- {score,hits[],missing[],comment}
    score       INT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_answer_session ON quiz_answer (session_id);
