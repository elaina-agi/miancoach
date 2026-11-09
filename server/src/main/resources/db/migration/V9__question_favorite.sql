-- 收藏公共题库中的整道题,只隔离用户与题目的关系,不复制答案/向量。
-- reindex 对仍存在的 doc/chunk 做 upsert 保留 ID;只有源题真正删除时才级联取消收藏。
CREATE TABLE question_favorite (
    user_id    UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    chunk_id   BIGINT NOT NULL REFERENCES chunk (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, chunk_id)
);

-- 用户自己的收藏按时间倒序分页;同一时刻收藏的题用 chunk_id 保证稳定排序。
CREATE INDEX idx_favorite_user_created ON question_favorite (user_id, created_at DESC, chunk_id DESC);
-- 删除源题时避免为外键级联扫描整张收藏表。
CREATE INDEX idx_favorite_chunk ON question_favorite (chunk_id);
