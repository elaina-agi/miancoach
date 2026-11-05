package com.autumn.rag.web;

import com.autumn.rag.common.AuthException;
import com.autumn.rag.common.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * /api/admin/** 只给管理员:没登录 401,登录了但不是管理员 403。
 *
 * <p>用拦截器按路径统一拦,而不是在每个管理接口里各自判断:以后新加的管理接口自动受保护,
 * 不会因为忘了写一行检查就把接口暴露出去。前端隐藏入口只是让界面干净,真正的防线在这里。
 *
 * <p>这里抛的异常照样由 GlobalExceptionHandler 转成 JSON 响应(preHandle 的异常也走 @ExceptionHandler)。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final CurrentUserResolver resolver;

    public AdminInterceptor(CurrentUserResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginUser user = resolver.resolve(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (user == null) {
            throw new AuthException("请先登录");
        }
        if (!user.isAdmin()) {
            throw new ForbiddenException("需要管理员权限");
        }
        return true;
    }
}
