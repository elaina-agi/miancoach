package com.autumn.rag.web;

import com.autumn.rag.dao.ChatDao;
import com.autumn.rag.service.AskService;
import com.autumn.rag.service.ChatService;
import io.swagger.v3.oas.annotations.Operation;
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

@Tag(name = "问答", description = "检索增强问答(SSE 流式,带引用)+ 历史会话")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AskService askService;
    private final ChatService chatService;

    public ChatController(AskService askService, ChatService chatService) {
        this.askService = askService;
        this.chatService = chatService;
    }

    public record AskReq(@NotBlank(message = "问题不能为空") String question,
                         UUID sessionId,                 // 历史会话 id,可空;传了才落历史
                         List<AskService.Turn> history) {} // 多轮追问:带上之前的问答,可空

    public record CreateSessionReq(@NotBlank(message = "首条问题不能为空") String firstQuestion) {}

    public record CreateSessionOut(UUID sessionId) {}

    // ------------------------------------------------------------------ 问答

    @Operation(summary = "提问(SSE)", description = "先推 event:refs(引用),再 event:delta 增量文本,最后 event:done;"
            + "带 history 时先把追问改写成独立问题再检索;带 sessionId 时把这一问一答落进该会话")
    @PostMapping("/ask")
    public SseEmitter ask(@RequestBody @Valid AskReq req) {
        return askService.ask(req.question(), req.sessionId(), req.history());
    }

    // ------------------------------------------------------------------ 历史会话

    @Operation(summary = "新建会话", description = "用首条问题当标题(截断 30 字);返回的 sessionId 后续随 /ask 传回,"
            + "问答才会落进这个会话。前端在进入新一轮对话、发第一条消息前调用")
    @PostMapping("/session")
    public CreateSessionOut createSession(@RequestBody @Valid CreateSessionReq req) {
        return new CreateSessionOut(chatService.createSession(req.firstQuestion()));
    }

    @Operation(summary = "历史会话列表", description = "按最后活跃时间倒序。"
            + "「今天/昨天/更早」的分组由前端按 updatedAt 自己算——展示逻辑且依赖用户时区,后端不替前端决定")
    @GetMapping("/sessions")
    public List<ChatDao.SessionRow> sessions(@RequestParam(defaultValue = "50") int limit) {
        return chatService.sessions(limit);
    }

    @Operation(summary = "某会话的全部消息", description = "按写入顺序返回,refs 已解析成对象,用于回放整段对话")
    @GetMapping("/session/{id}")
    public List<ChatService.MessageOut> session(@PathVariable UUID id) {
        return chatService.messages(id);
    }

    @Operation(summary = "删除会话", description = "消息由外键 ON DELETE CASCADE 一并清除;会话不存在返回 404")
    @DeleteMapping("/session/{id}")
    public Map<String, Object> deleteSession(@PathVariable UUID id) {
        chatService.delete(id);
        return Map.of("deleted", true);
    }
}
