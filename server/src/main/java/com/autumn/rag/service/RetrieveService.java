package com.autumn.rag.service;

import com.autumn.rag.corpus.MdParser;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.Hit;
import com.autumn.rag.llm.EmbeddingProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索:三路召回再合并去重。
 *   exact   题干归一化精确命中(FAQ 常问原题,直接命中)
 *   vector  问题向量 → pgvector 余弦近邻
 *   keyword 关键词 ILIKE 兜底(题干/正文)
 * 顺序决定优先级:exact > vector(相似度) > keyword。带 channel 标签便于评测时分析各路的贡献。
 *
 * 入口先做**向量模型指纹校验**:库里的向量和当前模型不是一套就直接报错,不给垃圾结果。
 */
@Service
public class RetrieveService {

    private static final Pattern LATIN = Pattern.compile("[A-Za-z0-9_]{2,}");

    private final CorpusDao dao;
    private final EmbeddingProvider embed;
    private final IndexMetaService indexMeta;

    public RetrieveService(CorpusDao dao, EmbeddingProvider embed, IndexMetaService indexMeta) {
        this.dao = dao;
        this.embed = embed;
        this.indexMeta = indexMeta;
    }

    public List<Hit> retrieve(String question, int topK) {
        indexMeta.assertCompatible();

        Set<Long> seen = new LinkedHashSet<>();
        List<Hit> merged = new ArrayList<>();

        // 1) 精确
        for (Hit h : dao.exactHits(MdParser.normalizeKey(question))) {
            if (seen.add(h.chunkId())) {
                merged.add(h);
            }
        }
        // 2) 向量(问题向量必须与入库同一模型算,否则空间对不上)
        float[] queryVector = embed.embed(List.of(question)).get(0);
        for (Hit h : dao.vectorTopK(queryVector, Math.max(topK * 2, 10))) {
            if (seen.add(h.chunkId())) {
                merged.add(h);
            }
        }
        // 3) 关键词
        for (String token : tokens(question)) {
            for (Hit h : dao.keywordHits(token, topK)) {
                if (seen.add(h.chunkId())) {
                    merged.add(h);
                }
            }
        }
        return merged.size() > topK ? merged.subList(0, topK) : merged;
    }

    /** 简单分词:全句 + 字母数字串 + 标点切出的长片段,够中文关键词兜底用。 */
    private List<String> tokens(String question) {
        List<String> out = new ArrayList<>();
        String whole = question.replaceAll("\\s+", "").trim();
        if (whole.length() >= 2 && whole.length() <= 60) {
            out.add(whole);
        }
        Matcher m = LATIN.matcher(question);
        while (m.find() && out.size() < 4) {
            out.add(m.group());
        }
        for (String seg : question.split("[，。？！、；：,.?!;: \\s]+")) {
            if (seg.length() >= 2 && seg.length() <= 20 && out.size() < 6) {
                out.add(seg);
            }
        }
        return out.stream().distinct().toList();
    }
}
