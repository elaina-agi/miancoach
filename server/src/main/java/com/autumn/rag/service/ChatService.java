package com.autumn.rag.service;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.ChatDao;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 问答历史:会话与消息的读写。结构对称于 {@link QuizService}。
 *
 * <p>分两半:
 * <ul>
 *   <li><b>查询侧</b>(会话列表 / 某会话消息 / 删除)——供前端历史侧栏用</li>
 *   <li><b>写入侧</b>({@link #recordQuestion} / {@link #recordAnswer})——供 {@link AskService}
 *       在问答流程的两个时刻调用。注意依赖方向是单向的:AskService → ChatService,不能反过来</li>
 * </ul>
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** 会话标题的长度上限(字符)。超出截断加省略号,只影响列表显示。 */
    private static final int TITLE_MAX = 30;
    /** 列表条数上限,防止前端传个巨大的 limit 把库拖垮。 */
    private static final int LIST_MAX = 200;

    private final ChatDao dao;
    private final ObjectMapper om;

    public ChatService(ChatDao dao, ObjectMapper om) {
        this.dao = dao;
        this.om = om;
    }

    /** 一条消息对外(和落库)的形状。refs 已从 JSON 文本解析成对象,前端直接拿数组。 */
    public record MessageOut(long id, String role, String content, boolean noAnswer,
                             Instant createdAt, List<ChatRef> refs) {}

    // ------------------------------------------------------------------ 查询侧

    /**
     * 历史会话列表,最近活跃在前。
     *
     * <p><b>分组(今天/昨天/更早)不在这里做</b>——那是展示逻辑,而且按哪个时区分组取决于
     * 用户所在的时区,后端不该替前端决定。这里只给排好序的扁平列表 + updatedAt 时间戳。
     */
    public List<ChatDao.SessionRow> sessions(int limit) {
        return dao.sessions(Math.max(1, Math.min(limit, LIST_MAX)));
    }

    /** 一个会话的全部消息,按写入顺序(前端回放整段对话用)。 */
    public List<MessageOut> messages(UUID sessionId) {
        return dao.messages(sessionId).stream()
                .map(r -> new MessageOut(r.id(), r.role(), r.content(), r.noAnswer(),
                        r.createdAt(), parseRefs(r.refsJson())))
                .toList();
    }

    /** 删会话(消息由外键级联删除)。会话不存在时抛 404,而不是静默成功。 */
    public void delete(UUID sessionId) {
        if (dao.deleteSession(sessionId) == 0) {
            throw new NotFoundException("会话不存在: " + sessionId);
        }
    }

    // ------------------------------------------------------------------ 写入侧(供 AskService)

    /**
     * 用首条问题建会话,返回新会话 id。
     * 标题取问题前 {@value #TITLE_MAX} 字——零成本,够列表辨认;将来要做重命名,
     * 直接 UPDATE title 即可,表结构不用动。
     */
    public UUID createSession(String firstQuestion) {
        String q = firstQuestion == null ? "" : firstQuestion.strip();
        String title = q.isEmpty()
                ? "新会话"
                : (q.length() > TITLE_MAX ? q.substring(0, TITLE_MAX) + "…" : q);
        return dao.createSession(title);
    }

    /**
     * 用户提问立刻落库。
     *
     * <p><b>为什么不等回答完一起存:</b>用户发完就关标签页的话,问题也应该留在历史里。
     * 代价是可能出现"有问无答"的记录(生成失败/中断),但那是事实,不该藏起来。
     */
    public void recordQuestion(UUID sessionId, String question) {
        dao.insertMessage(sessionId, "user", question, null, false);
        dao.touchSession(sessionId);
    }

    /**
     * 回答生成完毕后落库。
     *
     * @param refsJson 和 SSE 里推给前端的**同一份**引用快照,保证回放时卡片不缺
     * @param noAnswer 是否走了拒答(双门槛拦截),前端据此显示"语料外问题"标记
     */
    public void recordAnswer(UUID sessionId, String answer, String refsJson, boolean noAnswer) {
        dao.insertMessage(sessionId, "assistant", answer, refsJson, noAnswer);
        dao.touchSession(sessionId);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * refs 的 JSON 文本 → 对象。库里存的是我们自己写进去的内容,正常不会解析失败;
     * 真遇到坏数据也只让这一条的引用卡片不显示,**不能让整个会话读不出来**——
     * 但要走日志,别做成静默失败(否则坏了没人知道)。
     */
    private List<ChatRef> parseRefs(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return om.readValue(json, new TypeReference<List<ChatRef>>() {});
        } catch (Exception e) {
            log.warn("会话消息的 refs 解析失败,该条引用卡片将不显示: {}", e.getMessage());
            return null;
        }
    }
}
