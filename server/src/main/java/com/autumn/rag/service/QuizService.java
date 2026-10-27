package com.autumn.rag.service;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.ChunkFull;
import com.autumn.rag.dao.CorpusDao.Question;
import com.autumn.rag.dao.QuizDao;
import com.autumn.rag.dao.QuizDao.AnswerRow;
import com.autumn.rag.dao.QuizDao.SessionRow;
import com.autumn.rag.dao.QuizDao.WeakRow;
import com.autumn.rag.llm.ChatMessage;
import com.autumn.rag.llm.ChatProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 模拟面试:抽题 → 以标准原文为基准批改(LLM 结构化评分)→ 落库 → 复盘聚合。 */
@Service
public class QuizService {

    private final CorpusDao corpusDao;
    private final QuizDao quizDao;
    private final ChatProvider chat;
    private final ObjectMapper om;

    public QuizService(CorpusDao corpusDao, QuizDao quizDao, ChatProvider chat, ObjectMapper om) {
        this.corpusDao = corpusDao;
        this.quizDao = quizDao;
        this.chat = chat;
        this.om = om;
    }

    public record Grade(Integer score, List<String> hits, List<String> missing, String comment) {}

    public record StartOut(UUID sessionId, Long chunkId, String group, String category, String question) {}

    public record GradeOut(long answerId, String group, String category, String question,
                           String title, String url, Grade grade) {}

    // ------------------------------------------------------------------ 会话
    @Transactional
    public StartOut start(String groupTag) {
        UUID session = quizDao.createSession(groupTag);
        return pick(session, groupTag, List.of());
    }

    /**
     * 抽下一题,同一场里不重复:跳过本场已作答的题,以及前端传来的 exclude(出过但被跳过、没作答的题)。
     * 范围内的题全出过一遍后放开限制,允许重复,而不是报错让面试进行不下去。
     */
    @Transactional
    public StartOut pick(UUID sessionId, String groupTag, List<Long> exclude) {
        Set<Long> seen = new LinkedHashSet<>(quizDao.answeredChunkIds(sessionId));
        if (exclude != null) {
            exclude.stream().filter(Objects::nonNull).forEach(seen::add);
        }
        Question q = corpusDao.randomQuestion(groupTag, List.copyOf(seen));
        if (q == null && !seen.isEmpty()) {
            q = corpusDao.randomQuestion(groupTag);
        }
        if (q == null) {
            throw new NotFoundException("库里还没有题,请先 /api/admin/reindex 入库");
        }
        return new StartOut(sessionId, q.chunkId(), q.group(), q.category(), q.question());
    }

    // ------------------------------------------------------------------ 批改
    @Transactional
    public GradeOut grade(UUID sessionId, Long chunkId, String userAnswer) {
        ChunkFull full = corpusDao.getChunk(chunkId);
        if (full == null) {
            throw new NotFoundException("题目不存在: " + chunkId);
        }
        Grade grade = chat.isReal() ? gradeByLlm(full, userAnswer) : new Grade(null, List.of(), List.of(),
                "当前是 mock 模式,未真正批改。配置 BAGUWEN_CHAT_PROVIDER=openai + BAGUWEN_CHAT_API_KEY 后重试。");
        String gradeJson;
        try {
            gradeJson = om.writeValueAsString(grade);
        } catch (Exception e) {
            throw new IllegalStateException("序列化批改结果失败", e);
        }
        long answerId = quizDao.saveAnswer(sessionId, chunkId, full.group(), full.category(),
                full.question(), userAnswer, gradeJson, grade.score());
        return new GradeOut(answerId, full.group(), full.category(), full.question(),
                full.title(), full.url(), grade);
    }

    private Grade gradeByLlm(ChunkFull full, String userAnswer) {
        String prompt = "你是后端面试官,正在给候选人口述回答打分。下面是这道题的标准答案材料(权威)。\n"
                + "题目:" + full.question() + "\n\n"
                + "标准答案材料:\n" + full.content() + "\n\n"
                + "候选人口述回答:\n" + userAnswer + "\n\n"
                + "请批改:对照标准答案,指出答对的得分点和漏掉的关键点。"
                + "只输出一个 JSON 对象(不要 markdown 围栏),格式:\n"
                + "{\"score\":整数0-100,\"hits\":[\"答对的点\"],\"missing\":[\"漏掉的关键点\"],\"comment\":\"一句不超过120字的点评\"}";
        String raw = chat.chat(List.of(
                ChatMessage.system("你只输出合法 JSON,不输出其它任何内容。"),
                ChatMessage.user(prompt)));
        return parseGrade(raw);
    }

    private Grade parseGrade(String raw) {
        String s = raw == null ? "" : raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new Grade(null, List.of(), List.of(), "模型未返回有效 JSON,请重试或换 provider");
        }
        try {
            JsonNode n = om.readTree(s.substring(start, end + 1));
            Integer score = n.path("score").isNumber() ? clamp(n.path("score").asInt()) : null;
            List<String> hits = strList(n, "hits");
            List<String> missing = strList(n, "missing");
            String comment = n.path("comment").asText("");
            if (score == null && hits.isEmpty() && missing.isEmpty() && comment.isBlank()) {
                return new Grade(null, List.of(), List.of(), "模型返回了空批改,请重试");
            }
            return new Grade(score, hits, missing, comment);
        } catch (Exception e) {
            return new Grade(null, List.of(), List.of(), "批改解析失败: " + e.getMessage());
        }
    }

    private static List<String> strList(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        JsonNode arr = n.path(field);
        if (arr.isArray()) {
            arr.forEach(x -> {
                String t = x.asText("").trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            });
        }
        return out;
    }

    private static Integer clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    // ------------------------------------------------------------------ 复盘
    public List<SessionRow> sessions(int limit, boolean answeredOnly) {
        return quizDao.sessions(limit, answeredOnly);
    }

    public QuizDao.Overview overview() {
        return quizDao.overview();
    }

    public List<AnswerRow> sessionAnswers(UUID sessionId) {
        return quizDao.answers(sessionId);
    }

    public List<WeakRow> weakness() {
        return quizDao.weakness();
    }

    /**
     * 场次总结报告(LLM 汇总):requirements.md 5.4 承诺的接口。
     * 不落库,随用随生成(同一场次作答不变,重生成结果只受模型随机性影响)。
     */
    public ReportOut report(UUID sessionId) {
        List<AnswerRow> answers = quizDao.answers(sessionId);
        if (answers.isEmpty()) {
            throw new NotFoundException("场次不存在或还没有作答: " + sessionId);
        }
        StringBuilder sb = new StringBuilder();
        int graded = 0, scoreSum = 0;
        for (int i = 0; i < answers.size(); i++) {
            AnswerRow a = answers.get(i);
            Integer score = a.score();
            if (score != null) {
                graded++;
                scoreSum += score;
            }
            sb.append(i + 1).append(". 题目:").append(a.question())
                    .append("(板块:").append(a.groupTag() == null ? "未知" : a.groupTag())
                    .append(",得分:").append(score == null ? "未批改" : score).append(")\n");
            Grade g = parseGradeSafe(a.gradeJson());
            if (g != null) {
                if (!g.hits().isEmpty()) {
                    sb.append("   答对:").append(String.join("、", g.hits())).append('\n');
                }
                if (!g.missing().isEmpty()) {
                    sb.append("   漏掉:").append(String.join("、", g.missing())).append('\n');
                }
            }
        }
        double avg = graded == 0 ? 0 : (double) scoreSum / graded;
        String raw = chat.chat(List.of(
                ChatMessage.system("""
                        你是面试教练。根据一次模拟面试的逐题批改记录,输出一份简短复盘(markdown):
                        1. 整体表现一两句(结合平均分与答题质量);
                        2. 主要薄弱点(归纳共性,不超过 3 条);
                        3. 下一步复习建议(可执行,不超过 3 条)。
                        不要罗列每道题,不要输出 JSON。"""),
                ChatMessage.user("共 " + answers.size() + " 题,平均分 "
                        + String.format("%.1f", avg) + "。逐题记录:\n" + sb)));
        return new ReportOut(sessionId, answers.size(), avg, raw);
    }

    /** 批改 JSON 宽松解析(报告容错:某题批改坏了只影响该题,不影响整场报告)。 */
    private Grade parseGradeSafe(String gradeJson) {
        try {
            JsonNode n = om.readTree(gradeJson == null ? "" : gradeJson);
            return new Grade(null, strList(n, "hits"), strList(n, "missing"), n.path("comment").asText(""));
        } catch (Exception e) {
            return null;
        }
    }

    public record ReportOut(UUID sessionId, int answered, double avgScore, String report) {}
}
