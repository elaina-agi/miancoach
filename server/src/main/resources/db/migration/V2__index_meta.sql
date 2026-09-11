-- 索引指纹:记录"当前这批向量是哪个模型、多少维算出来的"。
-- 作用:换 embedding 模型后若没重新索引,查询向量与库内向量不在同一空间,
-- 会导致检索**静默变烂**(尤其同维度的不同模型)。有指纹就能把静默失败变成明确报错。
CREATE TABLE IF NOT EXISTS index_meta (
    id             INT PRIMARY KEY,
    embed_model    TEXT        NOT NULL,
    embed_dims     INT         NOT NULL,
    embed_provider TEXT        NOT NULL,
    chunk_count    INT         NOT NULL,
    indexed_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT index_meta_singleton CHECK (id = 1)
);
