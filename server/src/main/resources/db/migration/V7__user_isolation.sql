-- 用户体系第二步:问答历史、模拟面试记录按用户隔离。游客不落库(问答不传 sessionId,面试走无状态接口)。

-- 【旧数据直接清掉】
-- V6 之前的会话没有归属,补不出 user_id;留着的话要么所有人都看得到,要么谁都看不到,
-- 两种都没意义。消息 / 作答由外键 ON DELETE CASCADE 跟着删(TRUNCATE ... CASCADE 同理)。
TRUNCATE chat_session, quiz_session CASCADE;

-- 【用户删除 → 记录跟着删】历史是个人数据,账号没了留着也没人能看
ALTER TABLE chat_session ADD COLUMN user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE;
ALTER TABLE quiz_session ADD COLUMN user_id UUID NOT NULL REFERENCES app_user (id) ON DELETE CASCADE;

-- quiz_answer 不加 user_id:按用户聚合(薄弱点 / 总览)时 JOIN quiz_session 即可,
-- 冗余一列就得保证两边永远一致,换来的只是少一次按主键的 JOIN。

-- 列表都是"某个用户的、按时间倒序",原来只按时间的索引用不上了
DROP INDEX IF EXISTS idx_chat_sess_updated;
CREATE INDEX IF NOT EXISTS idx_chat_sess_user_updated ON chat_session (user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_quiz_sess_user_created ON quiz_session (user_id, created_at DESC);
