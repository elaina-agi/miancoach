package com.autumn.rag.service;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.UserDao;
import com.autumn.rag.dao.UserDao.AdminUserRow;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 后台的用户管理:列表、禁用 / 解禁、改角色、改昵称、删除。调用方已经过 AdminInterceptor,一定是管理员。
 *
 * <p><b>不能对自己做禁用、删除、取消管理员。</b>这样后台里永远至少剩一个管理员(操作者自己),
 * 不会把自己锁在门外——真锁住了只能去库里改。
 *
 * <p>管理员看不到、也改不了别人的密码:库里只有 BCrypt 哈希,忘记密码应该走用户自己的邮箱验证。
 */
@Service
public class AdminUserService {

    static final int NICKNAME_MAX = 20;
    private static final Set<String> ROLES = Set.of("USER", "ADMIN");

    private final UserDao userDao;

    public AdminUserService(UserDao userDao) {
        this.userDao = userDao;
    }

    public record Page(List<AdminUserRow> items, int total, int page, int size) {}

    /** page 从 1 开始;size 限制在 1~100,防止一次拉全表。 */
    public Page list(String keyword, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size));
        return new Page(userDao.adminList(keyword, s, (p - 1) * s), userDao.adminCount(keyword), p, s);
    }

    public void setDisabled(UUID operatorId, UUID userId, boolean disabled) {
        if (disabled && userId.equals(operatorId)) {
            throw new IllegalArgumentException("不能禁用自己的账号");
        }
        found(userDao.setDisabled(userId, disabled));
    }

    public void setRole(UUID operatorId, UUID userId, String role) {
        if (role == null || !ROLES.contains(role)) {
            throw new IllegalArgumentException("角色只能是 USER 或 ADMIN");
        }
        if (userId.equals(operatorId) && !"ADMIN".equals(role)) {
            throw new IllegalArgumentException("不能取消自己的管理员身份");
        }
        found(userDao.setRole(userId, role));
    }

    /** 传空 = 清空昵称,界面上改为显示邮箱。 */
    public void setNickname(UUID userId, String nickname) {
        String nick = (nickname == null || nickname.isBlank()) ? null : nickname.trim();
        if (nick != null && nick.length() > NICKNAME_MAX) {
            throw new IllegalArgumentException("昵称最多 " + NICKNAME_MAX + " 个字");
        }
        found(userDao.setNickname(userId, nick));
    }

    /** 问答、面试记录跟着级联删除,不可恢复。只想让他用不了的话应该用禁用。 */
    public void delete(UUID operatorId, UUID userId) {
        if (userId.equals(operatorId)) {
            throw new IllegalArgumentException("不能删除自己的账号");
        }
        found(userDao.delete(userId));
    }

    private static void found(boolean updated) {
        if (!updated) {
            throw new NotFoundException("用户不存在");
        }
    }
}
