-- 用户体系第一步:账号 + 邮箱验证码。此时还不拦截任何接口,chat/quiz 表也暂不加 user_id(下一阶段做隔离)。

-- 【id 用 UUID 而非 BIGSERIAL】
-- 自增 id 会暴露用户总数和注册顺序,而且可以遍历(/user/1、/user/2……)。与 chat_session 保持一致。
--
-- 【email 只存小写,用 CHECK 兜底】
-- 归一化在应用层做(AuthService 统一 toLowerCase),CHECK 防止哪天有人绕过应用直接插库,
-- 导致 Foo@x.com 和 foo@x.com 被当成两个账号。这样普通 UNIQUE 就够了,不需要 lower(email) 函数索引。
CREATE TABLE IF NOT EXISTS app_user (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash TEXT NOT NULL,                    -- BCrypt,自带盐和 cost,60 字符
    nickname      TEXT,                             -- 仅用于显示,可空;登录只认 email
    role          TEXT NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 【验证码表同时是审计日志】
-- 不在验证成功后删除行,而是写 consumed_at:冷却、限流("这个 IP 一小时发了几次")都直接 count 这张表,
-- 出问题时也能查到是谁在刷。单实例部署下用不着 Redis。
--
-- 【"新码发出后旧码失效"不靠 UPDATE】
-- 校验时只取该邮箱 + 用途下最新的一条,更早的码自然就作废了,不需要额外去改旧行。
CREATE TABLE IF NOT EXISTS email_code (
    id          BIGSERIAL PRIMARY KEY,
    email       TEXT NOT NULL,
    purpose     TEXT NOT NULL CHECK (purpose IN ('register', 'reset_password')),
    code_hash   TEXT NOT NULL,                      -- 不存明文:库泄漏时,未过期的码不能被直接拿去用
    attempts    INT  NOT NULL DEFAULT 0,            -- 输错次数,到上限即作废,防止暴力枚举 6 位数
    ip          INET,                               -- 请求方 IP,限流用
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,                        -- 非空 = 已使用,一次性
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 取"某邮箱某用途最新一条"(校验 + 60 秒冷却)
CREATE INDEX IF NOT EXISTS idx_email_code_latest ON email_code (email, purpose, created_at DESC);
-- 按 IP 统计近一小时的发送次数(限流那一步用)
CREATE INDEX IF NOT EXISTS idx_email_code_ip     ON email_code (ip, created_at DESC);
