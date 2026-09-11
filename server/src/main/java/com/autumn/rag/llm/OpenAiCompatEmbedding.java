package com.autumn.rag.llm;

import com.autumn.rag.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 向量端:OpenAI 兼容 /embeddings。显式传 dimensions,并校验返回维度与配置一致。 */
public class OpenAiCompatEmbedding implements EmbeddingProvider {

    private static final int ATTEMPTS = 3;

    private final AppProperties.Embed props;
    private final OpenAiHttp http;

    public OpenAiCompatEmbedding(AppProperties.Embed props, ObjectMapper om) {
        this.props = props;
        this.http = new OpenAiHttp(om, props.connectTimeoutSecs());
    }

    @Override
    public boolean isReal() {
        return true;
    }

    @Override
    public String model() {
        return props.model();
    }

    @Override
    public int dims() {
        return props.dims();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("input", texts);
        body.put("encoding_format", "float");
        if (props.sendDimensions()) {
            // 仅部分服务支持(百炼 v3/v4);硅基流动 bge-m3 传了会 400,所以默认关闭
            body.put("dimensions", props.dims());
        }

        JsonNode root = http.postJson(endpoint(), "/embeddings", body, ATTEMPTS);
        JsonNode data = root.path("data");
        List<float[]> out = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode emb = item.path("embedding");
            float[] vec = new float[emb.size()];
            for (int i = 0; i < vec.length; i++) {
                vec[i] = (float) emb.get(i).asDouble();
            }
            out.add(vec);
        }
        if (out.size() != texts.size()) {
            throw new LlmException("embedding 返回条数不符: " + out.size() + " vs " + texts.size());
        }
        for (float[] v : out) {
            if (v.length != props.dims()) {
                throw new LlmException("向量维度不符:模型 " + props.model() + " 返回 " + v.length
                        + " 维,但配置 app.embed.dims=" + props.dims()
                        + "。请把 dims 改成模型实际支持的维度(改完需重新索引)");
            }
        }
        return out;
    }

    private OpenAiHttp.Endpoint endpoint() {
        return new OpenAiHttp.Endpoint(props.baseUrl(), props.apiKey(), props.requestTimeoutSecs());
    }
}
