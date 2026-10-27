package com.autumn.rag.config;

import com.autumn.rag.web.CurrentUserResolver;
import com.autumn.rag.web.LoginUser;
import org.springdoc.core.utils.SpringDocUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    static {
        // LoginUser 由 CurrentUserResolver 从请求头解析,不是客户端传的参数,别让 Swagger 把它画成 query 参数
        SpringDocUtils.getConfig().addRequestWrapperToIgnore(LoginUser.class);
    }

    private final CurrentUserResolver currentUserResolver;

    public WebConfig(CurrentUserResolver currentUserResolver) {
        this.currentUserResolver = currentUserResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserResolver);
    }
}
