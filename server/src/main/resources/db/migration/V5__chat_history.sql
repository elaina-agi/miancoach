-- 问答历史:会话 + 消息两张表。结构与 quiz_session/quiz_answer 对称——一个会话挂多条记录。
--
-- 【refs 为什么存完整快照,而不是只存 chunk_id】
-- 引用卡片点开要读原文,读的是 /api/corpus/chunk/{chunkId}。而 reindex 是 "DELETE 后重插",
-- chunk 的 id 会全部重新生成,旧 id 永久消失(序列只增不减,不会重用)。
-- 所以重灌之后,重灌前存下的所有引用卡片都会 404。
-- 把 refs 整份 JSON 存进来,卡片上的题目/板块/标题/预览就永远显示得出来,
-- chunk_id 退化成"如果还有效就深链接过去"的尽力而为——降级得体面,不是漏洞。
-- 这和 eval_query 存 target_question 文本而非 chunk_id 是同一个理由。
--
-- 【title / updated_at 是冗余字段】
-- 两者理论上都能从 chat_message 算出来(首条问题、最后一条消息时间),
-- 但列表页要捞 N 个会话,不冗余就得对每行做一次子查询/窗口函数。
-- 这是典型的"读多写少,用空间换查询简单";代价是写入时要同步维护 updated_at(见 ChatDao.touchSession)。

CREATE TABLE IF NOT EXISTS chat_session (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    title      TEXT NOT NULL,                      -- 首条问题截断而成,供列表显示;留字段便于将来做重命名
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()  -- 最后一条消息的时间:列表排序 + 前端按天分组的依据
);

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGSERIAL PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES chat_session (id) ON DELETE CASCADE,
    role       TEXT NOT NULL,                      -- user | assistant。用 TEXT 不用 enum:将来加 system 角色不用 ALTER TYPE
    content    TEXT NOT NULL,                      -- 原文。回答是 markdown,内联 [n] 引用下标
    refs       JSONB,                              -- 助手消息的引用卡片快照;用户消息为 NULL
    no_answer  BOOLEAN NOT NULL DEFAULT false,     -- 助手这条是否走了拒答(双门槛拦截)
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 按 (session_id, id) 而不是 (session_id, created_at):
-- 消息按自增 id 顺序插入,查一个会话的消息时 WHERE session_id=? ORDER BY id 可直接走索引取有序结果;
-- created_at 在同一事务内可能完全相同,排序不稳定。
CREATE INDEX IF NOT EXISTS idx_chat_msg_session  ON chat_message (session_id, id);
-- 会话列表按 updated_at 倒序取前 N 条
CREATE INDEX IF NOT EXISTS idx_chat_sess_updated ON chat_session (updated_at DESC);
