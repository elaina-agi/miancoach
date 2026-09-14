package com.autumn.rag.llm;

import java.util.List;

/**
 * 重排端:cross-encoder,对"多路召回"的候选做统一相关性打分。
 * 题目级向量与子段级向量的余弦分数不可比,重排是让它们公平竞争的裁判。
 */
public interface RerankProvider {

    /** 是否是真实模型(否=mock,调用方应跳过重排、保持召回原序)。 */
    boolean isReal();

    /** 标识,用于日志(如 openai:BAAI/bge-reranker-v2-m3)。 */
    String name();

    /** 对每个文档打相关性分(与 documents 等长、一一对应,越高越相关)。 */
    double[] scores(String query, List<String> documents);
}
