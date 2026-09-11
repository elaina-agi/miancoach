-- 评测 golden query 集:让评测去"自环"。
-- 旧模式拿库里题干原文当 query 搜同一题:query 与文档完全相同,向量相似度恒近 1,
-- recall 是"假满分",说明不了真实检索质量。这里存三类评测用例:
--   rewrite  LLM 把题干改写成的"真实用户问法"(语义同、措辞不同)—— 正样本
--   probe    从长答案里提取的细节级追问(考"子问题检索")—— 正样本,目标=来源题
--   negative 明显超出语料域的问题 —— 负样本,期望检索层给出低相似度(供拒答阈值定标)
-- 不设 chunk 外键,target_question 存题干文本,评测时按 question_key 解析到当前 chunk:
-- reindex 重灌后 golden 集仍可用,才能做"分块/检索改动前后"的同集对比。
CREATE TABLE IF NOT EXISTS eval_query (
    id              BIGSERIAL PRIMARY KEY,
    kind            TEXT NOT NULL,             -- rewrite | probe | negative
    query           TEXT NOT NULL,
    target_question TEXT,                      -- 正样本:目标题干;negative 为 NULL
    note            TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (kind, query)
);
CREATE INDEX IF NOT EXISTS idx_eval_query_kind ON eval_query (kind);
