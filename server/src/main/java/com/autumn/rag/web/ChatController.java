package com.autumn.rag.web;

import com.autumn.rag.service.AskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "问答", description = "检索增强问答(SSE 流式,带引用)")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AskService askService;

    public ChatController(AskService askService) {
        this.askService = askService;
    }

    public record AskReq(@NotBlank(message = "问题不能为空") String question) {}

    @Operation(summary = "提问(SSE)", description = "先推 event:refs(引用),再 event:delta 增量文本,最后 event:done")
    @PostMapping("/ask")
    public SseEmitter ask(@RequestBody @Valid AskReq req) {
        return askService.ask(req.question());
    }
}
