package com.autumn.rag.web;

import com.autumn.rag.common.AuthException;
import com.autumn.rag.dao.ChatDao;
import com.autumn.rag.service.AskService;
import com.autumn.rag.service.ChatService;
import com.autumn.rag.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 问答游客也能用;历史会话只有登录用户有,每个人只看得到自己的。 */
@Tag(name = "问答", description = "检索增强问答(SSE 流式,带引用)+ 历史会话")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AskService askService;
    private final ChatService chatService;
    private final QuotaService quotaService;

    public ChatController(AskService askService, ChatService chatService, QuotaService quotaService) {
        this.askService = askService;
        this.chatService = chatService;
        this.quotaService = quotaService;
    }

    public record AskReq(@NotBlank(message = "问题不能为空") String question,
                         UUID sessionId,                 // 历史会话 id,可空;传了才落历史(须登录且是自己的会话)
                         List<AskService.Turn> history) {} // 多轮追问:带上之前的问答,可空

    public record CreateSessionReq(@NotBlank(message = "首条问题不能为空") String firstQuestion) {}

    public record CreateSessionOut(UUID sessionId) {}

    // ------------------------------------------------------------------ 问答

    @Operation(summary = "提问(SSE)", description = "先推 event:refs(引用),再 event:delta 增量文本,最后 event:done;"
            + "带 history 时先把追问改写成独立问题再检索;带 sessionId 时把这一问一答落进该会话。游客不传 sessionId")
    @PostMapping("/ask")
    public SseEmitter ask(@CurrentUser(required = false) LoginUser user, @RequestBody @Valid AskReq req) {
        // 归属在这里同步校验:进了 AskService 就是异步线程里边推流边写库,那时再报错只能塞进 SSE 的 error 事件
        if (req.sessionId() != null) {
            if (user == null) {
                throw new AuthException("请先登录");
            }
            chatService.requireOwner(user.id(), req.sessionId());
        }
        // 额度同理:超了要回成 HTTP 429,前端才能和"回答中途出错"区分开
        quotaService.consume(user == null ? null : user.id());
        return askService.ask(req.question(), req.sessionId(), req.history());
    }

    // ------------------------------------------------------------------ 历史会话

    @Operation(summary = "新建会话", description = "用首条问题当标题(截断 30 字);返回的 sessionId 后续随 /ask 传回,"
            + "问答才会落进这个会话。前端在进入新一轮对话、发第一条消息前调用")
    @PostMapping("/session")
    public CreateSessionOut createSession(@CurrentUser LoginUser user, @RequestBody @Valid CreateSessionReq req) {
        return new CreateSessionOut(chatService.createSession(user.id(), req.firstQuestion()));
    }

    @Operation(summary = "历史会话列表", description = "按最后活跃时间倒序。"
            + "「今天/昨天/更早」的分组由前端按 updatedAt 自己算——展示逻辑且依赖用户时区,后端不替前端决定")
    @GetMapping("/sessions")
    public List<ChatDao.SessionRow> sessions(@CurrentUser LoginUser user,
                                             @RequestParam(defaultValue = "50") int limit) {
        return chatService.sessions(user.id(), limit);
    }

    @Operation(summary = "某会话的全部消息", description = "按写入顺序返回,refs 已解析成对象,用于回放整段对话")
    @GetMapping("/session/{id}")
    public List<ChatService.MessageOut> session(@CurrentUser LoginUser user, @PathVariable UUID id) {
        return chatService.messages(user.id(), id);
    }

    @Operation(summary = "删除会话", description = "消息由外键 ON DELETE CASCADE 一并清除;会话不存在返回 404")
    @DeleteMapping("/session/{id}")
    public Map<String, Object> deleteSession(@CurrentUser LoginUser user, @PathVariable UUID id) {
        chatService.delete(user.id(), id);
        return Map.of("deleted", true);
    }
}
