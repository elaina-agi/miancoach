package com.autumn.rag.service;

import com.autumn.rag.config.AppProperties;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.ChunkFull;
import com.autumn.rag.dao.CorpusDao.Hit;
import com.autumn.rag.llm.ChatMessage;
import com.autumn.rag.llm.ChatProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * 检索增强问答:取 topN 篇原文当裁判 → 生成带 [n] 引用的流式回答。
 *
 * 两道防线:
 * 1. 拒答阈值:向量 top1 相似度低于 app.retrieval.min-vector-sim 时,不调 LLM,
 *    推 noAnswer 事件如实告知"语料库里没有"—— 宁可明说没有,不用不相关材料硬编。
 *    (阈值 0 = 关闭;定标依据 golden 评测的 posTopSimMin / negTopSimMax 区间。)
 * 2. 命中片段前置:parent-child 子段命中时,把该子段放在材料最前(再接父题全文),
 *    保证"藏在长答案里的细节"一定进入 LLM 视野,不再被字符预算截掉。
 */
@Service
public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    private final RetrieveService retrieve;
    private final CorpusDao dao;
    private final ChatProvider chat;
    private final ChatService chatService;
    private final AppProperties props;
    private final ObjectMapper om;
    private final ExecutorService executor;

    public AskService(RetrieveService retrieve, CorpusDao dao, ChatProvider chat, ChatService chatService,
                      AppProperties props, ObjectMapper om, ExecutorService executor) {
        this.retrieve = retrieve;
        this.dao = dao;
        this.chat = chat;
        this.chatService = chatService;
        this.props = props;
        this.om = om;
        this.executor = executor;
    }

    /** 多轮对话的一轮历史(追问改写与回答背景用)。 */
    public record Turn(String q, String a) {}

    /**
     * @param sessionId 历史会话 id,**可为 null**——不传就不落历史(方便手动调接口);
     *                  传了但会话已失效也不会导致回答失败(见 {@link #recordSafely})
     */
    public SseEmitter ask(String question, UUID sessionId, List<Turn> history) {
        SseEmitter emitter = new SseEmitter(0L); // 不自动超时,交给 done
        executor.execute(() -> {
            try {
                // 提问先落库:即使随后关页面、生成中断,问题也留痕。
                // 存的是**用户的原话**,不是下面改写后的检索词——历史要还原用户看到的东西。
                if (sessionId != null) {
                    recordSafely(() -> chatService.recordQuestion(sessionId, question), "提问");
                }

                // 多轮:追问带指代("那它怎么持久化的?"),先改写成独立问题再检索
                String searchQuery = question;
                Turn last = history == null || history.isEmpty() ? null : history.get(history.size() - 1);
                if (last != null && last.q() != null && !last.q().isBlank()) {
                    searchQuery = rewriteFollowUp(last, question);
                }

                List<Hit> hits = retrieve.retrieve(searchQuery, props.retrieval().topN());

                // 防线一:双门槛拒答 —— 向量余弦与 cross-encoder 分同时过低,
                // 才判"语料库里没有"。余弦分域内外有重叠(实测正样本最低 0.55 / 负样本最高 0.59),
                // 重排分区分度好得多(相关 ~0.3 / 无关 ~0.0002),两个都过不去的基本就是真域外。
                double topSim = hits.stream().filter(h -> "vector".equals(h.channel()))
                        .mapToDouble(Hit::sim).max().orElse(0.0);
                double topRerank = hits.stream().mapToDouble(Hit::rerankScore).max().orElse(0.0);
                double minSim = props.retrieval().minVectorSim();
                double minRerank = props.retrieval().minRerankScore();
                if (minSim > 0 && hits.stream().noneMatch(h -> "exact".equals(h.channel()))
                        && topSim < minSim && topRerank < minRerank) {
                    String refusal = "语料库里没有找到与这个问题相关的内容(当前语料覆盖:"
                            + "Java / MySQL / Redis / 消息队列 / 分布式 / 系统设计 / Linux / Git / Docker)。"
                            + "为避免误导,我不基于无关材料作答——建议换个问法,或补充题目相关背景。";
                    emit(emitter, "refs", om.writeValueAsString(List.of()));
                    emit(emitter, "delta", refusal);
                    // 拒答也是一次"完整的回答",照样落库(refs 为空、noAnswer=true),
                    // 否则历史里这条会变成有问无答,回看时不知道当时发生了什么
                    if (sessionId != null) {
                        recordSafely(() -> chatService.recordAnswer(sessionId, refusal, null, true), "拒答");
                    }
                    emit(emitter, "done", "{\"noAnswer\":true}");
                    return;
                }

                List<ChatRef> refs = new ArrayList<>();
                List<String> context = new ArrayList<>();
                int perHit = Math.max(300, props.retrieval().contextCharBudget() / Math.max(1, hits.size()) + 200);
                for (Hit h : hits) {
                    ChunkFull full = dao.getChunk(h.chunkId());
                    if (full == null) {
                        continue;
                    }
                    // 防线二:命中子段前置——细节一定进 LLM 视野
                    String segContent = h.segId() > 0 ? dao.getSegContent(h.segId()) : null;
                    String excerpt = segContent != null ? cut(segContent, 160) : cut(full.content(), 160);
                    int idx = refs.size() + 1;
                    refs.add(new ChatRef(full.chunkId(), full.question(), full.category(), full.group(),
                            full.title(), full.url(), excerpt));
                    int bodyBudget = Math.max(400, perHit - (segContent == null ? 0 : segContent.length() + 30));
                    String body = cut(full.content(), bodyBudget);
                    String material = segContent != null
                            ? "【材料" + idx + "】(" + full.group() + " · " + full.question() + ")\n"
                              + "«与问题最相关的片段»\n" + segContent + "\n\n«该题完整答案»\n" + body
                            : "【材料" + idx + "】(" + full.group() + " · " + full.question() + ")\n" + body;
                    context.add(material);
                }

                // 同一份引用快照,一份推给前端、一份随回答落库(保证回放时卡片不缺)
                String refsJson = om.writeValueAsString(refs);
                emit(emitter, "refs", refsJson);
                String material = String.join("\n\n", context);
                String userText = (last != null
                        ? "对话背景(上一轮):\n问:" + cut(last.q(), 200) + "\n答:" + cut(last.a(), 600)
                          + "\n\n本轮问题(可能是指代上一轮的追问):\n" + question + "\n\n"
                        : "")
                        + material
                        + "\n\n请回答下面这个问题,并按要求标注引用:\n" + question;
                List<ChatMessage> messages = List.of(
                        ChatMessage.system(SYSTEM),
                        ChatMessage.user(userText));
                // 边推给前端边攒全文——落库要的是完整答案
                StringBuilder answer = new StringBuilder();
                chat.chatStream(messages, delta -> {
                    emit(emitter, "delta", delta);
                    answer.append(delta);
                });
                if (sessionId != null) {
                    recordSafely(() -> chatService.recordAnswer(sessionId, answer.toString(), refsJson, false), "回答");
                }
                emit(emitter, "done", "{}");
            } catch (Exception e) {
                emit(emitter, "error", e.getMessage() == null ? e.toString() : e.getMessage());
            } finally {
                emitter.complete();
            }
        });
        return emitter;
    }

    private static final String SYSTEM = """
            你是一名严谨、资深的后端面试官。你将看到若干篇参考资料,用【材料n】标号。
            要求:
            1. 只依据材料作答,中文、先给结论再展开;
            2. 引用到的材料在相应结论后用[n]标注;
            3. 材料不足时如实说明"材料里没有涉及",绝不编造;
            4. 语言精炼,适合面试背诵;
            5. 输出必须严格套用下面的 Markdown 模板——每个要点独立一行、以"- "开头,
               绝对禁止把多个要点写进同一段落;加粗只包住"结论："和要点标题本身:

            **结论：**<一句话结论>[n]

            - **<要点标题>**：<要点内容>[n]
            - **<要点标题>**：<要点内容>[n]

            > 考点提示：<一句话,可省略>
            """;

    private static final String REWRITE_SYSTEM = """
            你是检索查询改写器。给定上一轮问答和本轮追问,把追问改写成一个**独立、完整、不带指代**的检索查询:
            1. 把"它/这个/那/上面说的"等指代替换成上一轮里的具体对象;
            2. 只补全指代,不要扩写、不要回答、不要加入上一轮没有的新话题;
            3. 只输出改写后的问题本身,不要任何解释或引号。若追问本身已完整独立,原样输出。
            """;

    /** 追问 → 独立检索问题;改写失败降级用原问题(追问可检索性差一点,总比整个问答挂掉强)。 */
    private String rewriteFollowUp(Turn last, String question) {
        try {
            String rewritten = chat.chat(List.of(
                    ChatMessage.system(REWRITE_SYSTEM),
                    ChatMessage.user("上一轮:\n问:" + cut(last.q(), 200) + "\n答:" + cut(last.a(), 600)
                            + "\n\n本轮追问:" + question)));
            String s = rewritten == null ? "" : rewritten.trim();
            return s.isEmpty() || s.length() > 200 ? question : s;
        } catch (Exception e) {
            return question;
        }
    }

    /**
     * 落库失败不能让用户拿不到答案:回答是主功能,历史记录是附加,
     * 会话 id 失效、库抖动之类都不该把一次成功的问答变成错误。
     * 但必须走 WARN 日志——静默吞掉的话,历史悄悄丢了没人知道。
     */
    private void recordSafely(Runnable action, String what) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("{} 落库失败(回答不受影响,仅历史记录缺失): {}", what, e.getMessage());
        }
    }

    private void emit(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException e) {
            throw new IllegalStateException("SSE 推送中断", e);
        }
    }

    private static String cut(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
