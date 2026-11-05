package com.autumn.rag.web;

import com.autumn.rag.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 后台用户管理。路径在 /api/admin 下,管理员校验由 AdminInterceptor 统一做;
 * 这里的 @CurrentUser 只是为了拿到操作者 id,用来拦"对自己禁用 / 删除 / 降级"。
 */
@Tag(name = "管理 · 用户", description = "仅管理员:用户列表、禁用、改角色、改昵称、删除")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private final AdminUserService service;

    public AdminUserController(AdminUserService service) {
        this.service = service;
    }

    public record DisabledReq(@NotNull(message = "不能为空") Boolean disabled) {}

    public record RoleReq(@NotBlank(message = "不能为空") String role) {}

    /** nickname 为 null 或空串 = 清空昵称;长度在 Service 里校验 */
    public record NicknameReq(String nickname) {}

    @Operation(summary = "用户列表", description = "按注册时间倒序;q 按邮箱 / 昵称模糊搜索;page 从 1 开始")
    @GetMapping
    public AdminUserService.Page list(@RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return service.list(q, page, size);
    }

    @Operation(summary = "禁用 / 解禁", description = "禁用后对方立即退出登录,也登录不了;历史记录保留。不能禁用自己")
    @PutMapping("/{id}/disabled")
    public Map<String, Object> setDisabled(@CurrentUser LoginUser me, @PathVariable UUID id,
                                           @RequestBody @Valid DisabledReq req) {
        service.setDisabled(me.id(), id, req.disabled());
        return Map.of("ok", true);
    }

    @Operation(summary = "改角色", description = "USER / ADMIN,立即生效。不能取消自己的管理员")
    @PutMapping("/{id}/role")
    public Map<String, Object> setRole(@CurrentUser LoginUser me, @PathVariable UUID id,
                                       @RequestBody @Valid RoleReq req) {
        service.setRole(me.id(), id, req.role());
        return Map.of("ok", true);
    }

    @Operation(summary = "改昵称", description = "传空 = 清空,界面上改为显示邮箱")
    @PutMapping("/{id}/nickname")
    public Map<String, Object> setNickname(@PathVariable UUID id, @RequestBody NicknameReq req) {
        service.setNickname(id, req.nickname());
        return Map.of("ok", true);
    }

    @Operation(summary = "删除用户", description = "问答、面试记录一并删除,不可恢复。不能删除自己")
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@CurrentUser LoginUser me, @PathVariable UUID id) {
        service.delete(me.id(), id);
        return Map.of("ok", true);
    }
}
