package com.autumn.rag.llm;

import com.autumn.rag.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配两个独立提供者:生成端与向量端各管各的 base-url / key / model。 */
@Configuration
public class LlmConfig {

    @Bean
    public ChatProvider chatProvider(AppProperties props, ObjectMapper om) {
        AppProperties.Chat c = props.chat();
        if (useReal(c.provider(), c.apiKey(), "app.chat")) {
            return new OpenAiCompatChat(c, om);
        }
        return new MockChat();
    }

    @Bean
    public EmbeddingProvider embeddingProvider(AppProperties props, ObjectMapper om) {
        AppProperties.Embed e = props.embed();
        if (useReal(e.provider(), e.apiKey(), "app.embed")) {
            return new OpenAiCompatEmbedding(e, om);
        }
        return new MockEmbedding(e);
    }

    /**
     * provider 语义:
     *   auto(默认) 有 key 就用真实模型,没 key 自动 mock —— 免配置跑通
     *   openai     显式要求真实;缺 key 直接启动失败(不静默降级,免得以为接上了其实是 mock)
     *   mock       强制 mock
     */
    private static boolean useReal(String provider, String apiKey, String prefix) {
        String p = (provider == null || provider.isBlank()) ? "auto" : provider.trim().toLowerCase();
        boolean hasKey = apiKey != null && !apiKey.isBlank();
        return switch (p) {
            case "mock" -> false;
            case "auto" -> hasKey;
            case "openai" -> {
                if (!hasKey) {
                    throw new IllegalStateException(prefix + ".provider=openai 但没配 apiKey。"
                            + "请在项目根 .env 里填对应的 KEY(或保持 auto,没 key 时会自动走 mock)。");
                }
                yield true;
            }
            default -> throw new IllegalStateException(
                    prefix + ".provider 只支持 auto|openai|mock,当前值: " + provider);
        };
    }
}
