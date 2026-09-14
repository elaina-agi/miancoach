package com.autumn.rag.llm;

import com.autumn.rag.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重排端:OpenAI 兼容系 /rerank(硅基流动 BAAI/bge-reranker-v2-m3,免费)。
 * 请求 {model, query, documents[], top_n, return_documents:false},
 * 响应 results[{index, relevance_score}] —— 按分数降序返回,但要还原回调用方的文档下标。
 */
public class OpenAiCompatRerank implements RerankProvider {

    private static final int ATTEMPTS = 2;
    private static final int MAX_DOCS_PER_CALL = 32; // 单请求文档上限,超了分批

    private final AppProperties.Rerank props;
    private final OpenAiHttp http;

    public OpenAiCompatRerank(AppProperties.Rerank props, ObjectMapper om) {
        this.props = props;
        this.http = new OpenAiHttp(om, props.connectTimeoutSecs());
    }

    @Override
    public boolean isReal() {
        return true;
    }

    @Override
    public String name() {
        return "openai:" + props.model();
    }

    @Override
    public double[] scores(String query, List<String> documents) {
        double[] out = new double[documents.size()];
        for (int from = 0; from < documents.size(); from += MAX_DOCS_PER_CALL) {
            List<String> batch = documents.subList(from, Math.min(from + MAX_DOCS_PER_CALL, documents.size()));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", props.model());
            body.put("query", query);
            body.put("documents", batch);
            body.put("top_n", batch.size());
            body.put("return_documents", false);

            JsonNode root = http.postJson(endpoint(), "/rerank", body, ATTEMPTS);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                throw new LlmException("rerank 返回 results 为空");
            }
            for (JsonNode r : results) {
                int index = r.path("index").asInt(-1);
                if (index >= 0 && index < batch.size()) {
                    out[from + index] = r.path("relevance_score").asDouble(0.0);
                }
            }
        }
        return out;
    }

    private OpenAiHttp.Endpoint endpoint() {
        return new OpenAiHttp.Endpoint(props.baseUrl(), props.apiKey(), props.requestTimeoutSecs());
    }
}
