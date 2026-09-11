package com.autumn.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 应用配置。刻意把**生成端(chat)**和**向量端(embed)**拆成两套,各带 base-url / key / model:
 * - 生成端随时可换厂商/模型/key,不影响已入库的向量;
 * - 向量端一旦换模型,库里向量就失效,**必须重新索引**(见 IndexMetaService 的指纹校验)。
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Corpus corpus,
        Chat chat,
        Embed embed,
        Retrieval retrieval) {

    public record Corpus(String dir) {}

    /** 生成端:问答、批改、总结。 */
    public record Chat(
            String provider,          // auto(有 key 即真实) | openai | mock
            String baseUrl,
            String apiKey,
            String model,
            int requestTimeoutSecs,
            int connectTimeoutSecs) {}

    /** 向量端:索引与查询。dims 必须与 embedding 列维度一致。 */
    public record Embed(
            String provider,          // auto | openai | mock
            String baseUrl,
            String apiKey,
            String model,
            int dims,
            int batchSize,
            /**
             * 是否在请求里显式传 dimensions。默认 false —— 因为不是所有 OpenAI 兼容服务都认这个参数
             * (例如硅基流动的 BAAI/bge-m3 会直接 400: parameter is invalid)。
             * 百炼 text-embedding-v3/v4 需要指定非默认维度时再打开。
             * 无论开关,返回向量都会与 dims 校验,不一致立刻报错。
             */
            boolean sendDimensions,
            int requestTimeoutSecs,
            int connectTimeoutSecs) {}

    public record Retrieval(int topK, int contextCharBudget, double minVectorSim) {}
}
