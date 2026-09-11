-- parent-child 分块:长答案按内部结构切"子段",子段向量做检索,命中后回父题取全文喂模型。
-- 动机(实测 case):问"AOF 重写的具体流程是怎样的?"——正确题(Redis 持久化方式)能排到 top1,
-- 但该题答案混着 RDB/AOF/混合持久化一大篇,拼 prompt 按预算截断后细节没进上下文,
-- LLM 只能答"材料里没有涉及"。子段级向量让"藏在长答案里的细节"可被直接检索命中;
-- 检索命中子段后仍回父题取全文(答案完整、引用溯源还是题目级)。
CREATE TABLE IF NOT EXISTS chunk_seg (
    id         BIGSERIAL PRIMARY KEY,
    chunk_id   BIGINT NOT NULL REFERENCES chunk (id) ON DELETE CASCADE,
    seq        INT    NOT NULL,               -- 子段在父题内的序号(0 起)
    content    TEXT   NOT NULL,
    embedding  vector(1024),
    UNIQUE (chunk_id, seq)
);
CREATE INDEX IF NOT EXISTS idx_chunk_seg_chunk ON chunk_seg (chunk_id);
CREATE INDEX IF NOT EXISTS idx_chunk_seg_embedding
    ON chunk_seg USING hnsw (embedding vector_cosine_ops);

-- 索引指纹补充分块方案:换了切分方案(子段表内容)也必须重灌,否则子段与父题向量不配套
ALTER TABLE index_meta ADD COLUMN IF NOT EXISTS chunking TEXT NOT NULL DEFAULT 'flat';
