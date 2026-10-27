package com.autumn.rag.web;

import com.autumn.rag.common.AuthException;
import com.autumn.rag.dao.UserDao;
import com.autumn.rag.service.JwtService;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.util.UUID;

/**
 * 解析 {@link CurrentUser} 参数。用参数解析器而不是全局拦截器:只有声明了要用户的接口才解析 token,
 * 题库、登录这些接口带着一个过期 token 也照常能用(登录接口要是被过期 token 拦住就死循环了)。
 *
 * <p><b>带了 token 但无效时直接 401,不降级成游客,哪怕接口允许游客。</b>
 * 前端以为自己登录着,后端却按游客处理,问答就悄悄不进历史了,用户完全不知道;
 * 401 能让前端清掉失效的 token,界面切回游客状态,用户看得到发生了什么。
 */
@Component
public class CurrentUserResolver implements HandlerMethodArgumentResolver {

    private static final String BEARER = "Bearer ";

    private final JwtService jwt;
    private final UserDao users;

    public CurrentUserResolver(JwtService jwt, UserDao users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && parameter.getParameterType() == LoginUser.class;
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        LoginUser user = resolve(webRequest.getHeader(HttpHeaders.AUTHORIZATION));
        CurrentUser ann = parameter.getParameterAnnotation(CurrentUser.class);
        if (user == null && ann != null && ann.required()) {
            throw new AuthException("请先登录");
        }
        return user;
    }

    /** 没带 token:游客,返回 null。带了但签名不对、过期、用户已删:401。 */
    LoginUser resolve(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return null;
        }
        if (!authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            throw new AuthException("登录已失效,请重新登录");
        }
        UUID id = jwt.parse(authorization.substring(BEARER.length()).trim())
                .orElseThrow(() -> new AuthException("登录已失效,请重新登录"));
        return users.findById(id)
                .map(u -> new LoginUser(u.id(), u.role()))
                .orElseThrow(() -> new AuthException("登录已失效,请重新登录"));
    }
}
