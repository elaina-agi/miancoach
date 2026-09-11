package com.autumn.rag.llm;

import java.util.List;

/**
 * 向量端:文本 → 向量。
 * **模型标识(model)与维度(dims)会被写进 index_meta 做指纹**,检索前校验一致性:
 * 换模型后若没重新索引,查询向量与库内向量不在同一空间,必须报错而不是静默给出垃圾结果。
 */
public interface EmbeddingProvider {

    /** 是否是真实模型。 */
    boolean isReal();

    /** 模型指纹:相同字符串 + 相同维度 ⇒ 认为是同一向量空间。 */
    String model();

    /** 输出维度,必须与 chunk.embedding 列维度一致。 */
    int dims();

    /** 文本 → 向量,入参顺序与返回一一对应。 */
    List<float[]> embed(List<String> texts);
}
