package com.autumn.rag.service;

import com.autumn.rag.config.AppProperties;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.ChunkFull;
import com.autumn.rag.dao.CorpusDao.Hit;
import com.autumn.rag.llm.ChatMessage;
import com.autumn.rag.llm.ChatProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/** 检索增强问答:取 topK 原文当裁判 → 生成带 [n] 引用的流式回答。 */
@Service
public class AskService {

    private final RetrieveService retrieve;
    private final CorpusDao dao;
    private final ChatProvider chat;
    private final AppProperties props;
    private final ObjectMapper om;
    private final ExecutorService executor;

    public AskService(RetrieveService retrieve, CorpusDao dao, ChatProvider chat,
                      AppProperties props, ObjectMapper om, ExecutorService executor) {
        this.retrieve = retrieve;
        this.dao = dao;
        this.chat = chat;
        this.props = props;
        this.om = om;
        this.executor = executor;
    }

    /** 引用卡片(前端点击看原文)。 */
    public record Ref(long chunkId, String question, String category, String group,
                      String title, String url, String excerpt) {}

    public SseEmitter ask(String question) {
        SseEmitter emitter = new SseEmitter(0L); // 不自动超时,交给 done
        executor.execute(() -> {
            try {
                List<Hit> hits = retrieve.retrieve(question, props.retrieval().topK());
                List<Ref> refs = new ArrayList<>();
                List<String> context = new ArrayList<>();
                int perHit = Math.max(300, props.retrieval().contextCharBudget() / Math.max(1, hits.size()) + 200);
                for (Hit h : hits) {
                    ChunkFull full = dao.getChunk(h.chunkId());
                    if (full == null) {
                        continue;
                    }
                    int idx = refs.size() + 1;
                    refs.add(new Ref(full.chunkId(), full.question(), full.category(), full.group(),
                            full.title(), full.url(), cut(full.content(), 160)));
                    String body = cut(full.content(), perHit);
                    context.add("【材料" + idx + "】(" + full.group() + " · " + full.question() + ")\n" + body);
                }

                emit(emitter, "refs", om.writeValueAsString(refs));
                String material = String.join("\n\n", context);
                String userText = material + "\n\n请回答下面这个问题,并按要求标注引用:\n" + question;
                List<ChatMessage> messages = List.of(
                        ChatMessage.system(SYSTEM),
                        ChatMessage.user(userText));
                chat.chatStream(messages, delta -> emit(emitter, "delta", delta));
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
            4. 语言精炼,适合面试背诵,可补一句"考点提示"但不得超出材料。
            """;

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
