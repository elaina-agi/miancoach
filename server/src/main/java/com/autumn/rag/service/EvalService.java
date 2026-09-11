package com.autumn.rag.service;

import com.autumn.rag.config.AppProperties;
import com.autumn.rag.corpus.MdParser;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.Hit;
import com.autumn.rag.dao.CorpusDao.Question;
import com.autumn.rag.dao.EvalQueryDao;
import com.autumn.rag.dao.EvalQueryDao.Row;
import com.autumn.rag.llm.EmbeddingProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索自评,两种模式:
 *
 * mode=self(旧,保留当回归):抽 n 道题、拿题干原文当 query 搜同一题。
 *   query 与文档完全相同 → 向量相似度恒近 1,数字是"假满分",只能验证链路没坏。
 *
 * mode=golden(去自环):eval_query 表里的三类用例跑真实检索管线:
 *   rewrite/probe  正样本:LLM 改写的用户问法、长答案细节探针 → 报 recall@1/@k、MRR
 *   negative       负样本:域外问题 → 报向量 top1 相似度分布与"拒答率"
 *   并输出正/负样本 top1 相似度区间,用来给"拒答阈值"定标。
 *   评测指标全部基于"部署管线"(三路召回合并),检索改动前后跑同一 golden 集,数字可直接对比。
 */
@Service
public class EvalService {

    private final CorpusDao dao;
    private final EvalQueryDao evalQueryDao;
    private final RetrieveService retrieve;
    private final EmbeddingProvider embed;
    private final IndexMetaService indexMeta;
    private final AppProperties props;

    public EvalService(CorpusDao dao, EvalQueryDao evalQueryDao, RetrieveService retrieve,
                       EmbeddingProvider embed, IndexMetaService indexMeta, AppProperties props) {
        this.dao = dao;
        this.evalQueryDao = evalQueryDao;
        this.retrieve = retrieve;
        this.embed = embed;
        this.indexMeta = indexMeta;
        this.props = props;
    }

    // ------------------------------------------------------------------ 旧:自环回归
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

    // ------------------------------------------------------------------ 新:golden 评测
    /** 单条未命中的明细,便于分析检索到底漏在哪。rank=0 表示 topK 内没出现。 */
    public record MissSample(String kind, String query, String target, int rank, double topSim) {}

    /** 单条负样本的明细:给拒答阈值定标用(看域外问题 top1 相似度都落在哪)。 */
    public record NegativeSample(String query, double topSim, boolean rejected) {}

    public record GoldenEvalOut(
            int sampled, int unresolved, int negatives, int topK,
            double recall1, double recallK, double mrr,
            double avgLatencyMs, double p95LatencyMs,
            int negativesRejected, double negativeRejectRate, double rejectSim,
            double posTopSimAvg, double posTopSimMin, double negTopSimMax,
            List<MissSample> misses, List<NegativeSample> negativeSamples,
            String embedModel, boolean embedReal) {}

    /**
     * @param n        抽样正样本数(<=0 全量;golden 集 ~1200 条时全量较慢)
     * @param kind     null/空=全部;可只评 rewrite|probe
     * @param rejectSim 负样本判拒的相似度阈值;<0 用配置 app.retrieval.min-vector-sim
     */
    public GoldenEvalOut golden(int n, int topK, String kind, double rejectSim) {
        indexMeta.assertCompatible();
        double threshold = rejectSim >= 0 ? rejectSim : props.retrieval().minVectorSim();

        List<Row> positives = new ArrayList<>();
        List<Row> negatives = new ArrayList<>();
        for (Row r : evalQueryDao.list(kind)) {
            if ("negative".equals(r.kind())) {
                negatives.add(r);
            } else if (r.targetQuestion() != null && !r.targetQuestion().isBlank()) {
                positives.add(r);
            }
        }
        Collections.shuffle(positives, new java.util.Random(42)); // 固定种子:改动前后跑同一批样本,数字可直接对比
        if (n > 0 && positives.size() > n) {
            positives = positives.subList(0, n);
        }

        int unresolved = 0;
        int hit1 = 0, hitK = 0, counted = 0;
        double mrrSum = 0, posSimSum = 0, posSimMin = 1.0;
        List<Long> latencies = new ArrayList<>();
        List<MissSample> misses = new ArrayList<>();

        for (Row p : positives) {
            Set<Long> targets = new HashSet<>(evalQueryDao.resolveTarget(p.targetQuestion()));
            if (targets.isEmpty()) {
                unresolved++; // 题干已随语料更新消失,跳过不计分
                continue;
            }
            long t0 = System.currentTimeMillis();
            List<Hit> hits = retrieve.retrieve(p.query(), topK);
            latencies.add(System.currentTimeMillis() - t0);

            int rank = 0;
            for (int i = 0; i < hits.size(); i++) {
                if (targets.contains(hits.get(i).chunkId())) {
                    rank = i + 1;
                    break;
                }
            }
            double topSim = hits.stream().filter(h -> "vector".equals(h.channel()))
                    .findFirst().map(Hit::sim).orElse(0.0);
            posSimSum += topSim;
            posSimMin = Math.min(posSimMin, topSim);

            counted++;
            if (rank == 1) {
                hit1++;
            }
            if (rank > 0) {
                hitK++;
                mrrSum += 1.0 / rank;
            } else if (misses.size() < 10) {
                misses.add(new MissSample(p.kind(), p.query(), p.targetQuestion(), 0, topSim));
            }
        }

        int rejected = 0;
        double negSimSum = 0, negSimMax = 0;
        List<NegativeSample> negDetail = new ArrayList<>();
        for (Row neg : negatives) {
            long t0 = System.currentTimeMillis();
            List<Hit> hits = retrieve.retrieve(neg.query(), topK);
            latencies.add(System.currentTimeMillis() - t0);
            double topSim = hits.stream().filter(h -> "vector".equals(h.channel()))
                    .findFirst().map(Hit::sim).orElse(0.0);
            negSimSum += topSim;
            negSimMax = Math.max(negSimMax, topSim);
            boolean isRejected = topSim < threshold;
            if (isRejected) {
                rejected++;
            }
            negDetail.add(new NegativeSample(neg.query(), topSim, isRejected));
        }

        int posN = Math.max(1, counted);
        int latN = Math.max(1, latencies.size());
        Collections.sort(latencies);
        double p95 = latencies.get((int) Math.min(latN - 1, Math.ceil(latN * 0.95) - 1));
        int negN = Math.max(1, negatives.size());

        return new GoldenEvalOut(
                positives.size(), unresolved, negatives.size(), topK,
                (double) hit1 / posN, (double) hitK / posN, mrrSum / posN,
                latencies.stream().mapToLong(Long::longValue).average().orElse(0), p95,
                rejected, (double) rejected / negN, threshold,
                counted == 0 ? 0 : posSimSum / counted, counted == 0 ? 0 : posSimMin, negSimMax,
                misses, negDetail, embed.model(), embed.isReal());
    }
}
