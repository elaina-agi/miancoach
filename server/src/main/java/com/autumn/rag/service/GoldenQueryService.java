package com.autumn.rag.service;

import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.LongChunk;
import com.autumn.rag.dao.CorpusDao.Question;
import com.autumn.rag.dao.EvalQueryDao;
import com.autumn.rag.llm.ChatMessage;
import com.autumn.rag.llm.ChatProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 构建"去自环"的 golden 评测集(落 eval_query 表):
 *   rewrite  每道题干让 LLM 改写成 2 个真实用户问法 —— 替代"拿原题搜原题"
 *   probe    从最长的一批答案里,让 LLM 提一个"细节级"追问 —— 考子问题/细节检索
 *   negative 一组人工核验过、确定不在语料域的问题 —— 给拒答阈值定标
 * 全部写题干文本不写 chunk id,重灌索引后评测集仍然有效。
 */
@Service
public class GoldenQueryService {

    private static final Logger log = LoggerFactory.getLogger(GoldenQueryService.class);

    /** 批量改写时一次 LLM 请求带的题数。 */
    private static final int REWRITE_BATCH = 8;
    private static final int PROBE_CONTENT_LIMIT = 4000;

    /**
     * 负样本:确定不在语料域(语料只有 java/database/mq/分布式/系统设计/linux/git/docker,
     * 构建前逐条 grep 语料确认无命中)。
     */
    private static final List<String> NEGATIVES = List.of(
            "Kubernetes 的 Pod 是怎么调度的？",
            "Vue3 的响应式系统是怎么实现的？",
            "Go 语言 channel 的底层实现是什么？",
            "Python 的 GIL 是什么，怎么绕过它？",
            "前端首屏加载慢有哪些优化手段？",
            "DNS 解析的完整过程是怎样的？",
            "快速排序的时间复杂度是多少，怎么证明？",
            "UDP 编程中如何设计一个可靠传输协议？",
            "区块链的工作量证明和权益证明有什么区别？",
            "React 的 Fiber 架构解决了什么问题？");

    private final CorpusDao corpusDao;
    private final EvalQueryDao evalQueryDao;
    private final ChatProvider chat;
    private final ObjectMapper om;

    public GoldenQueryService(CorpusDao corpusDao, EvalQueryDao evalQueryDao,
                              ChatProvider chat, ObjectMapper om) {
        this.corpusDao = corpusDao;
        this.evalQueryDao = evalQueryDao;
        this.chat = chat;
        this.om = om;
    }

    public record BuildResult(int rewrite, int probe, int negative, long costMs) {}

    /**
     * 重建 golden 集。only=null 全量(先清后建);only=rewrite|probe|negative 只重建那一类。
     * 单个批次/探针失败只跳过该条,不中断整个构建(之前一次 LLM 异常会丢掉后面全部负样本)。
     */
    public BuildResult build(int rewriteLimit, int probeCount, String only) {
        long t0 = System.currentTimeMillis();
        boolean all = only == null || only.isBlank();
        int rewrite = 0, probe = 0;
        if (all || "rewrite".equals(only)) {
            evalQueryDao.clear(all ? null : "rewrite");
            rewrite = buildRewrites(rewriteLimit);
        }
        if (all || "probe".equals(only)) {
            evalQueryDao.clear(all ? null : "probe");
            probe = buildProbes(probeCount);
        }
        if (all || "negative".equals(only)) {
            evalQueryDao.clear(all ? null : "negative");
            for (String neg : NEGATIVES) {
                evalQueryDao.insert("negative", neg, null, "人工核验:语料中无此主题");
            }
        }
        return new BuildResult(rewrite, probe, (all || "negative".equals(only)) ? NEGATIVES.size() : 0,
                System.currentTimeMillis() - t0);
    }

    // ------------------------------------------------------------------ rewrite
    private int buildRewrites(int limit) {
        List<Question> questions = corpusDao.allQuestions();
        if (limit > 0 && questions.size() > limit) {
            questions = questions.subList(0, limit);
        }
        int made = 0;
        for (int i = 0; i < questions.size(); i += REWRITE_BATCH) {
            List<Question> batch = questions.subList(i, Math.min(i + REWRITE_BATCH, questions.size()));
            made += rewriteBatch(batch);
        }
        return made;
    }

    private int rewriteBatch(List<Question> batch) {
        List<String> titles = batch.stream().map(Question::question).toList();
        String user;
        try {
            user = om.writeValueAsString(titles);
        } catch (Exception e) {
            throw new IllegalStateException("序列化题干失败", e);
        }
        String resp;
        try {
            resp = chat.chat(List.of(
                    ChatMessage.system(REWRITE_SYSTEM),
                    ChatMessage.user(user)));
        } catch (Exception e) {
            log.warn("改写批次失败(跳过 {} 题): {}", batch.size(), e.getMessage());
            return 0;
        }
        JsonNode arr = parseJsonArray(resp);
        int made = 0;
        for (JsonNode node : arr) {
            String original = node.path("q").asText("");
            JsonNode rewrites = node.path("r");
            if (original.isBlank() || !rewrites.isArray()) {
                continue;
            }
            // 题干可能被 LLM 原样带回,也可能有轻微差异;按原文题干对齐,对不上就跳过
            Question target = batch.stream()
                    .filter(q -> q.question().equals(original))
                    .findFirst().orElse(null);
            if (target == null) {
                continue;
            }
            for (JsonNode r : rewrites) {
                String query = r.asText("").trim();
                if (query.length() >= 4) {
                    evalQueryDao.insert("rewrite", query, target.question(), null);
                    made++;
                }
            }
        }
        return made;
    }

    private static final String REWRITE_SYSTEM = """
            你是检索评测集构建器。输入是若干道面试题的题干(JSON 数组)。
            给每道题生成 2 个"真实用户会怎么问"的改写,要求:
            1. 语义考察点与原题一致,但措辞明显不同(不许只加减标点或换个语气词);
            2. 口语化、自然,像求职者当面提问,允许带一点场景(如"项目中怎么用");
            3. 长度 8~50 字,不出现"题目/这道题/题干"等字眼;
            4. 只输出 JSON 数组,形如 [{"q":"原题原文","r":["改写1","改写2"]}],不要任何多余文字。
            """;

    // ------------------------------------------------------------------ probe
    private int buildProbes(int probeCount) {
        if (probeCount <= 0) {
            return 0;
        }
        // 只挑最长的答案出细节探针:短答案没有"藏在深处的细节"
        List<LongChunk> longs = corpusDao.longestChunks(probeCount * 3);
        int made = 0;
        for (LongChunk c : longs) {
            if (made >= probeCount) {
                break;
            }
            var full = corpusDao.getChunk(c.chunkId());
            if (full == null) {
                continue;
            }
            String content = full.content();
            if (content.length() < 600) {
                continue;
            }
            String body = content.length() > PROBE_CONTENT_LIMIT ? content.substring(0, PROBE_CONTENT_LIMIT) : content;
            String resp;
            try {
                resp = chat.chat(List.of(
                        ChatMessage.system(PROBE_SYSTEM),
                        ChatMessage.user("题干:" + full.question() + "\n答案原文:\n" + body)));
            } catch (Exception e) {
                log.warn("探针生成失败(跳过: {}): {}", full.question(), e.getMessage());
                continue;
            }
            String probe = parseJson(resp).path("q").asText("").trim();
            if (probe.length() >= 6) {
                evalQueryDao.insert("probe", probe, full.question(), "细节探针");
                made++;
            }
        }
        return made;
    }

    private static final String PROBE_SYSTEM = """
            你是检索评测集构建器。给你一道面试题的完整答案原文。
            从原文中挑一个"细节级考点",把它变成一个独立的具体问题,要求:
            1. 答案原文里必须能明确找到该问题的答案(不是泛泛的总述,而是某个机制/步骤/参数/对比点);
            2. 问题独立成句,不出现"文中/原文/材料"等字眼;
            3. 8~40 字;
            4. 只输出 JSON {"q":"问题"},不要多余文字。
            """;

    // ------------------------------------------------------------------ 解析容错
    private JsonNode parseJsonArray(String resp) {
        String s = resp == null ? "" : resp.trim();
        int l = s.indexOf('[');
        int r = s.lastIndexOf(']');
        if (l < 0 || r <= l) {
            log.warn("改写返回不是 JSON 数组: {}", snippet(resp));
            return om.createArrayNode();
        }
        try {
            return om.readTree(s.substring(l, r + 1));
        } catch (Exception e) {
            log.warn("改写 JSON 解析失败: {}", snippet(resp));
            return om.createArrayNode();
        }
    }

    private JsonNode parseJson(String resp) {
        String s = resp == null ? "" : resp.trim();
        int l = s.indexOf('{');
        int r = s.lastIndexOf('}');
        if (l < 0 || r <= l) {
            return om.createObjectNode();
        }
        try {
            return om.readTree(s.substring(l, r + 1));
        } catch (Exception e) {
            log.warn("探针 JSON 解析失败: {}", snippet(resp));
            return om.createObjectNode();
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
