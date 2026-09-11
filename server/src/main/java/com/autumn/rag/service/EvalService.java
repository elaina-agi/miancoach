package com.autumn.rag.service;

import com.autumn.rag.corpus.MdParser;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.Hit;
import com.autumn.rag.dao.CorpusDao.Question;
import com.autumn.rag.llm.EmbeddingProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 检索自评:抽 n 道题当 golden query,看检索能否把"该题自己"召回。
 * - exactHit      题干归一化精确命中
 * - vectorHit1/5  向量召回命中 top1 / top5
 * 这是"分块/索引/检索改动"回归的数字依据(用真实语料做 recall@k)。
 * 注:mock 模式下向量是伪随机,数字仅验证链路;接真实 embedding 才有语义意义。
 */
@Service
public class EvalService {

    private final CorpusDao dao;
    private final EmbeddingProvider embed;
    private final IndexMetaService indexMeta;

    public EvalService(CorpusDao dao, EmbeddingProvider embed, IndexMetaService indexMeta) {
        this.dao = dao;
        this.embed = embed;
        this.indexMeta = indexMeta;
    }

    public record EvalOut(int sample, int exactHit, int vectorHit1, int vectorHit5,
                          double exactRecall, double vectorRecall1, double vectorRecall5,
                          String embedModel, boolean embedReal) {}

    public EvalOut selfRecall(int sampleN, int topK) {
        indexMeta.assertCompatible(); // 模型对不上时,评出来的数字也是假的,先拦住

        List<Question> questions = dao.randomQuestions(sampleN);
        int exact = 0, hit1 = 0, hit5 = 0;
        for (Question q : questions) {
            if (dao.exactHits(MdParser.normalizeKey(q.question())).stream()
                    .anyMatch(h -> h.chunkId() == q.chunkId())) {
                exact++;
            }
            float[] vector = embed.embed(List.of(q.question())).get(0);
            List<Hit> hits = dao.vectorTopK(vector, topK);
            if (!hits.isEmpty() && hits.get(0).chunkId() == q.chunkId()) {
                hit1++;
            }
            if (hits.stream().anyMatch(h -> h.chunkId() == q.chunkId())) {
                hit5++;
            }
        }
        int n = Math.max(1, questions.size());
        return new EvalOut(questions.size(), exact, hit1, hit5,
                (double) exact / n, (double) hit1 / n, (double) hit5 / n,
                embed.model(), embed.isReal());
    }
}
