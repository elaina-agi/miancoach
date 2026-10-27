package com.autumn.rag.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在 Controller 的 {@link LoginUser} 参数上,由 {@link CurrentUserResolver} 从请求头解析当前用户。
 *
 * <pre>
 *   sessions(@CurrentUser LoginUser user)                    // 必须登录,游客 401
 *   ask(@CurrentUser(required = false) LoginUser user)       // 游客也能用,游客时 user == null
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {

    boolean required() default true;
}
