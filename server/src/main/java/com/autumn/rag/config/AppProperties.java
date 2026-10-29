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
        Retrieval retrieval,
        Rerank rerank,
        Auth auth,
        Mail mail,
        Quota quota) {

    public record Corpus(String dir) {}

    /** 生成端:问答、批改、总结。 */
    public record Chat(
            String provider,          // auto(有 key 即真实) | openai | mock
            String baseUrl,
            String apiKey,
            String model,
            int requestTimeoutSecs,
            int connectTimeoutSecs,
            // 代理(可选):base-url 指向需代理的端点时配置(如 Google Gemini 官方 API 经本机代理)。
            // 只作用于 chat —— embed/rerank 走国内端点,保持直连。host 留空或端口 0 = 直连。
            String proxyHost,
            int proxyPort) {}

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

    /**
     * 检索三个 K,按漏斗从宽到窄:
     * <pre>
     *   vectorK(10)  每路向量召回条数,即打到 pgvector 的 LIMIT(题目级/子段级各取这么多)
     *      ↓
     *   candidateK(20)  合并去重后送进 cross-encoder 重排的候选数(见 {@link Rerank})
     *      ↓
     *   topN(5)      重排后最终进 prompt 的材料数
     * </pre>
     * 必须满足 {@code vectorK ≤ candidateK} 且 {@code topN < candidateK} ——
     * 否则重排只是给固定的几条换顺序,没法把原本排名靠后的正确答案捞回来。
     * <p>
     * 对照主流命名(本项目早期把 topN 叫 topK,与惯例相反,已更正):
     * vectorK ≈ LlamaIndex {@code similarity_top_k} / LangChain {@code search_kwargs.k};
     * topN    ≈ Cohere Rerank 与各家 reranker 的 {@code top_n}。
     * <p>
     * 拒答双门槛:余弦分与重排分**同时**低于阈值才拒(评测的 pos/neg 区间定标)。
     * 余弦分域内外有重叠,重排分(cross-encoder)区分度好得多。
     */
    public record Retrieval(int vectorK, int topN, int contextCharBudget,
                            double minVectorSim, double minRerankScore) {}

    /**
     * 重排端:cross-encoder 对多路召回统一排序。
     * 动机:题目级向量与子段级向量(parent-child)的相似度**不可比**,混池排序会互相挤位;
     * 召回做宽、重排做准是标准解法。key 缺省复用向量端的硅基流动 key。
     */
    public record Rerank(
            String provider,          // auto(有 key 即真实) | openai | mock
            String baseUrl,
            String apiKey,
            String model,
            int candidateK,           // 送入重排的候选数(从合并后的召回列表取前 N)
            int requestTimeoutSecs,
            int connectTimeoutSecs) {}

    /**
     * 账号与验证码。jwtSecret 留空时 JwtService 会随机生成(仅开发用,重启后所有 token 失效)。
     * 验证码的三个数字共同决定暴力枚举的成本:5 次 / 10 分钟,对一百万种组合基本不可能猜中。
     */
    public record Auth(
            String jwtSecret,
            int tokenTtlHours,
            int codeTtlMinutes,
            int codeMaxAttempts,
            int codeCooldownSecs) {}
        
    /**
     * 发信端,和 chat/embed 同一套 provider 约定。SMTP 服务器/账号/密码用 Spring 自带的 spring.mail.*。
     * from 只放地址;fromName 是收件箱里显示的发件人名,放 yml 而不是 .env(.env 里写中文会乱码)。
     */
    public record Mail(
            String provider,          // auto | smtp | mock
            String from,
            String fromName) {}

    /**
     * 调大模型的接口(问答 / 批改 / 总结)每天的次数上限,控成本。0 = 不限。
     * guestDaily 是所有游客合用的一个额度,userDaily 是每个登录用户各自的(为什么这样分见 QuotaService)。
     */
    public record Quota(int guestDaily, int userDaily) {}
}
