package com.autumn.rag.service;

import com.autumn.rag.config.AppProperties;
import com.autumn.rag.corpus.MdParser;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.Hit;
import com.autumn.rag.llm.EmbeddingProvider;
import com.autumn.rag.llm.RerankProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 检索:多路召回 → 统一重排。
 *   exact   题干归一化精确命中(FAQ 常问原题,直接命中)
 *   vector  问题向量 → pgvector 余弦近邻,**题目级 + 子段级两路**(parent-child:
 *           子段命中升级到父题,segId 带给生成端做"命中片段前置")
 *   keyword 关键词 ILIKE 兜底(题干/正文)
 * 合并后取前 candidateK 个候选,交给 cross-encoder **统一重排**再截 topN ——
 * 题目级与子段级向量的余弦分数不可比,混池排序会互相挤位(实测混合 recall@1 因此回退),
 * 重排是让多粒度召回公平竞争的裁判;重排挂掉时降级回召回原序,不挡服务。
 *
 * 入口先做**向量模型指纹校验**:库里的向量和当前模型/分块方案不是一套就直接报错,不给垃圾结果。
 */
@Service
public class RetrieveService {

    private static final Logger log = LoggerFactory.getLogger(RetrieveService.class);
    private static final Pattern LATIN = Pattern.compile("[A-Za-z0-9_]{2,}");

    private final CorpusDao dao;
    private final EmbeddingProvider embed;
    private final IndexMetaService indexMeta;
    private final RerankProvider rerank;
    private final AppProperties props;

    public RetrieveService(CorpusDao dao, EmbeddingProvider embed, IndexMetaService indexMeta,
                           RerankProvider rerank, AppProperties props) {
        this.dao = dao;
        this.embed = embed;
        this.indexMeta = indexMeta;
        this.rerank = rerank;
        this.props = props;
    }

    /**
     * @param topN 重排后最终返回的条数(= 进 prompt 的材料数)。
     *             注意召回宽度由 {@code app.retrieval.vector-k} 独立控制,**不再由 topN 推导** ——
     *             早期写法是 {@code vecK = max(topN*2, 10)},把"召回多宽"和"输出多少条"耦在了一起。
     */
    public List<Hit> retrieve(String question, int topN) {
        indexMeta.assertCompatible();

        Set<Long> seen = new LinkedHashSet<>();
        List<Hit> merged = new ArrayList<>();

        // 1) 精确
        for (Hit h : dao.exactHits(MdParser.normalizeKey(question))) {
            if (seen.add(h.chunkId())) {
                merged.add(h);
            }
        }
        // 2) 向量:题目级与子段级各取 vectorK 条,同池按相似度排序(子段命中升级到父题)
        float[] queryVector = embed.embed(List.of(question)).get(0);
        int vectorK = Math.max(1, props.retrieval().vectorK());
        List<Hit> vecMerged = new ArrayList<>(dao.vectorTopK(queryVector, vectorK));
        vecMerged.addAll(dao.vectorSegTopK(queryVector, vectorK));
        vecMerged.sort((a, b) -> Double.compare(b.sim(), a.sim()));
        for (Hit h : vecMerged) {
            if (seen.add(h.chunkId())) {
                merged.add(h);
            }
        }
        // 3) 关键词
        for (String token : tokens(question)) {
            for (Hit h : dao.keywordHits(token, topN)) {
                if (seen.add(h.chunkId())) {
                    merged.add(h);
                }
            }
        }
        // 4) 统一重排:候选取前 candidateK,cross-encoder 重新排序后截 topN
        int candidateK = Math.max(topN, props.rerank().candidateK());
        List<Hit> candidates = merged.size() > candidateK ? new ArrayList<>(merged.subList(0, candidateK)) : merged;
        if (rerank.isReal() && candidates.size() > 1) {
            candidates = rerank(question, candidates);
        }
        return candidates.size() > topN ? candidates.subList(0, topN) : candidates;
    }

    /** cross-encoder 重排;失败降级回召回原序(重排不是正确性的前提,不能因为它挡服务)。 */
    private List<Hit> rerank(String question, List<Hit> candidates) {
        try {
            List<Long> ids = candidates.stream().map(Hit::chunkId).toList();
            Map<Long, String> texts = dao.rerankTexts(ids);
            List<Long> segIds = candidates.stream().map(Hit::segId).filter(id -> id > 0).toList();
            Map<Long, String> segTexts = dao.segTexts(segIds);
            List<String> docs = new ArrayList<>(ids.size());
            for (Hit h : candidates) {
                if (h.segId() > 0) {
                    // 子段命中:重排证据就是命中的子段(细节常在父题中后部,截前 800 字会截没)
                    String seg = segTexts.getOrDefault(h.segId(), "");
                    docs.add(seg.isEmpty()
                            ? texts.getOrDefault(h.chunkId(), question)
                            : h.question() + "\n" + (seg.length() > 800 ? seg.substring(0, 800) : seg));
                } else {
                    docs.add(texts.getOrDefault(h.chunkId(), question));
                }
            }
            double[] scores = rerank.scores(question, docs);
            List<Hit> out = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                Hit h = candidates.get(i);
                out.add(new Hit(h.chunkId(), h.group(), h.title(), h.url(), h.category(),
                        h.question(), h.sim(), h.channel(), h.segId(), scores[i]));
            }
            // 分数降序;同分保持原序(stable sort)
            java.util.List<Integer> idx = new ArrayList<>();
            for (int i = 0; i < out.size(); i++) {
                idx.add(i);
            }
            idx.sort((a, b) -> Double.compare(scores[b], scores[a]));
            return idx.stream().map(out::get).toList();
        } catch (Exception e) {
            log.warn("重排失败,降级回召回原序: {}", e.getMessage());
            return candidates;
        }
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
