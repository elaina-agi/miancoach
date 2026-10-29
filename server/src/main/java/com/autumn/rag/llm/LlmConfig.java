package com.autumn.rag.llm;

import com.autumn.rag.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/** 装配三个独立提供者:生成端、向量端、重排端各管各的 base-url / key / model。生成端可另配备用模型。 */
@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public ChatProvider chatProvider(AppProperties props, ObjectMapper om) {
        AppProperties.Chat c = props.chat();
        if (!useReal(c.provider(), c.apiKey(), "app.chat")) {
            return new MockChat();
        }
        AppProperties.Chat.Backup b = c.backup();
        if (b == null || b.apiKey() == null || b.apiKey().isBlank()) {
            return new OpenAiCompatChat(c, om, 3);
        }
        // 有备用时主模型不再自己重试:失败一次就切,重试那几秒留给备用
        FallbackChat chat = new FallbackChat(new OpenAiCompatChat(c, om, 1), new OpenAiCompatChat(c.asBackup(), om, 3),
                b.breakerFailures(), Duration.ofSeconds(b.breakerOpenSecs()));
        log.info("生成端为 {},主模型失败时切到备用", chat.name());
        return chat;
    }

    @Bean
    public EmbeddingProvider embeddingProvider(AppProperties props, ObjectMapper om) {
        AppProperties.Embed e = props.embed();
        if (useReal(e.provider(), e.apiKey(), "app.embed")) {
            return new OpenAiCompatEmbedding(e, om);
        }
        return new MockEmbedding(e);
    }

    @Bean
    public RerankProvider rerankProvider(AppProperties props, ObjectMapper om) {
        AppProperties.Rerank r = props.rerank();
        if (useReal(r.provider(), r.apiKey(), "app.rerank")) {
            return new OpenAiCompatRerank(r, om);
        }
        // mock 重排 = "永远不重排"(调用方按 isReal() 跳过),不需要独立实现类
        return new RerankProvider() {
            @Override
            public boolean isReal() {
                return false;
            }

            @Override
            public String name() {
                return "mock:no-rerank";
            }

            @Override
            public double[] scores(String query, List<String> documents) {
                throw new UnsupportedOperationException("mock rerank 不打分");
            }
        };
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
