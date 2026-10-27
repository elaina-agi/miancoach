package com.autumn.rag.web;

import com.autumn.rag.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Swagger 里的 Authorize 按钮由 @SecurityScheme 生成。不能直接用 @RequestHeader 让它出一个输入框:
 * OpenAPI 规范规定名为 Authorization 的 header 参数会被忽略,Swagger UI 根本不显示。
 */
@SecurityScheme(name = "bearer", type = SecuritySchemeType.HTTP, scheme = "bearer", bearerFormat = "JWT")
@Tag(name = "账号", description = "邮箱验证码注册 / 密码登录")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    public record CodeReq(@NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email) {}

    public record RegisterReq(
            @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式不正确") String email,
            @NotBlank(message = "验证码不能为空") @Pattern(regexp = "\\d{6}", message = "验证码是 6 位数字") String code,
            @NotBlank(message = "密码不能为空") String password, // 长度规则在 AuthService,按字节算
            @Size(max = 20, message = "昵称最多 20 个字") String nickname) {}

    public record LoginReq(@NotBlank(message = "邮箱不能为空") String email,
                           @NotBlank(message = "密码不能为空") String password) {}

    @Operation(summary = "发送注册验证码", description = "邮箱是否已注册,返回都一样(防邮箱枚举);同一邮箱 60 秒内只能发一次,否则 429")
    @PostMapping("/code")
    public Map<String, Object> sendCode(@RequestBody @Valid CodeReq req, HttpServletRequest http) {
        authService.sendRegisterCode(req.email(), http.getRemoteAddr());
        return Map.of("sent", true);
    }

    @Operation(summary = "注册", description = "校验验证码后建号,直接返回 token(不用再登录一次)")
    @PostMapping("/register")
    public AuthService.LoginOut register(@RequestBody @Valid RegisterReq req) {
        return authService.register(req.email(), req.code(), req.password(), req.nickname());
    }

    @Operation(summary = "登录", description = "邮箱不存在与密码错误返回同一个 401")
    @PostMapping("/login")
    public AuthService.LoginOut login(@RequestBody @Valid LoginReq req) {
        return authService.login(req.email(), req.password());
    }

    @Operation(summary = "当前用户", description = "请求头 Authorization: Bearer <token>;Swagger 里先点右上角 Authorize 填 token")
    @SecurityRequirement(name = "bearer")
    @GetMapping("/me")
    public AuthService.UserView me(@CurrentUser LoginUser user) {
        return authService.me(user.id());
    }
}
