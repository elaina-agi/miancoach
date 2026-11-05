-- 管理后台第一期:禁用账号 + 最近登录时间。
--
-- 【管理员沿用 V6 的 role 列,不建角色 / 权限表】
-- 只有 USER / ADMIN 两种角色、一个管理员,一列就够;RBAC 那套是给角色多、权限要细分的场景准备的。
--
-- 【设管理员不写在迁移里】
-- 迁移会在所有环境执行(本地、线上、别人克隆后自己部署的库),谁是管理员是某个环境的数据,不是表结构;
-- 仓库又是公开的,邮箱写进来就公开了。上线后在库里手动执行一次:
--   UPDATE app_user SET role = 'ADMIN' WHERE email = '<管理员邮箱>';

-- 【禁用用时间戳而不是布尔】NULL = 正常,非空 = 什么时候被禁用的,顺带留了记录。
-- 禁用而不删:历史记录还在,解封后原样恢复;删除账号另有接口,历史会跟着级联删掉(见 V7)
ALTER TABLE app_user ADD COLUMN disabled_at TIMESTAMPTZ;

-- 登录 / 注册成功时更新,只用于后台用户列表展示
ALTER TABLE app_user ADD COLUMN last_login_at TIMESTAMPTZ;
