package com.autumn.rag.llm;

import com.autumn.rag.config.AppProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Mock 向量:由文本哈希确定性地生成伪随机向量(同文本同向量,维度与配置一致)。
 * 用途是让无 key 时全链路可跑通。注意它**没有语义**,recall 无意义——所以 model() 里带上
 * 配置的模型名做指纹,接真实模型后必须重新索引,否则会被 IndexMetaService 拦下。
 */
public class MockEmbedding implements EmbeddingProvider {

    private final AppProperties.Embed props;

    public MockEmbedding(AppProperties.Embed props) {
        this.props = props;
    }

    @Override
    public boolean isReal() {
        return false;
    }

    @Override
    public String model() {
        return "mock(" + props.model() + ")";
    }

    @Override
    public int dims() {
        return props.dims();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) {
            out.add(deterministic(t, props.dims()));
        }
        return out;
    }

    private static float[] deterministic(String text, int dims) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < 8; i++) {
                seed = (seed << 8) | (hash[i] & 0xff);
            }
            Random rnd = new Random(seed);
            float[] v = new float[dims];
            for (int i = 0; i < dims; i++) {
                v[i] = rnd.nextFloat() * 2 - 1;
            }
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
