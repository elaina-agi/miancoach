package com.autumn.rag.web;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.QuizDao.AnswerRow;
import com.autumn.rag.dao.QuizDao.SessionRow;
import com.autumn.rag.dao.QuizDao.WeakRow;
import com.autumn.rag.service.QuizService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "模拟面试", description = "抽题-作答-批改-复盘")
@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final QuizService quizService;

    public QuizController(QuizService quizService) {
        this.quizService = quizService;
    }

    public record ScopeReq(String groupTag) {}

    public record StartResp(UUID sessionId, Long chunkId, String group, String category, String question) {}

    public record GradeReq(@NotNull Long chunkId, @NotBlank String userAnswer) {}

    @Operation(summary = "开一场模拟面试,返回第一题")
    @PostMapping("/start")
    public QuizService.StartOut start(@RequestBody(required = false) ScopeReq body) {
        return quizService.start(body == null ? null : body.groupTag());
    }

    @Operation(summary = "在当前会话里抽下一题")
    @PostMapping("/session/{id}/pick")
    public QuizService.StartOut pick(@PathVariable UUID id, @RequestBody(required = false) ScopeReq body) {
        return quizService.pick(id, body == null ? null : body.groupTag());
    }

    @Operation(summary = "批改一题(以标准原文为基准,结构化评分),落库")
    @PostMapping("/session/{id}/grade")
    public QuizService.GradeOut grade(@PathVariable UUID id, @RequestBody @Valid GradeReq req) {
        return quizService.grade(id, req.chunkId(), req.userAnswer());
    }

    @Operation(summary = "历史会话列表")
    @GetMapping("/sessions")
    public List<SessionRow> sessions(@RequestParam(defaultValue = "50") int limit) {
        return quizService.sessions(limit);
    }

    @Operation(summary = "某次会话的全部作答")
    @GetMapping("/session/{id}")
    public List<AnswerRow> session(@PathVariable UUID id) {
        return quizService.sessionAnswers(id);
    }

    @Operation(summary = "薄弱聚合:按板块/类目统计平均分与漏点")
    @GetMapping("/weakness")
    public List<WeakRow> weakness() {
        return quizService.weakness();
    }
}
