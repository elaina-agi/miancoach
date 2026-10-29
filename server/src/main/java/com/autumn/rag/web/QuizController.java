package com.autumn.rag.web;

import com.autumn.rag.dao.QuizDao;
import com.autumn.rag.dao.QuizDao.AnswerRow;
import com.autumn.rag.dao.QuizDao.SessionRow;
import com.autumn.rag.dao.QuizDao.WeakRow;
import com.autumn.rag.service.QuizService;
import com.autumn.rag.service.QuotaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 登录用户走 /session/{id}/... 这组,场次和作答落库,复盘页能回看;
 * 游客走 /pick、/grade、/report 这组无状态接口,什么都不存,本场数据只在前端页面里。
 * 批改和总结要调大模型,计入每日额度(QuotaService);抽题不调,不计。
 */
@Tag(name = "模拟面试", description = "抽题-作答-批改-复盘")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/quiz")
public class QuizController {

    private final QuizService quizService;
    private final QuotaService quotaService;

    public QuizController(QuizService quizService, QuotaService quotaService) {
        this.quizService = quizService;
        this.quotaService = quotaService;
    }

    /** exclude:本场出过但没作答的题(点了「换一题」),再抽时跳过;登录用户已作答的后端自己查 */
    public record ScopeReq(String groupTag, List<Long> exclude) {}

    public record GradeReq(@NotNull Long chunkId, @NotBlank String userAnswer) {}

    public record ReportReq(@NotEmpty(message = "这一场还没有作答")
                            @Size(max = 50, message = "一场最多 50 题") List<QuizService.ReportItem> answers) {}

    @Operation(summary = "开一场模拟面试,返回第一题", description = "登录时建场次并返回 sessionId;游客不建场次,不返回 sessionId")
    @PostMapping("/start")
    public QuizService.StartOut start(@CurrentUser(required = false) LoginUser user,
                                      @RequestBody(required = false) ScopeReq body) {
        return quizService.start(user == null ? null : user.id(), body == null ? null : body.groupTag());
    }

    // ------------------------------------------------------------------ 登录用户:场次落库

    @Operation(summary = "在当前场次里抽下一题")
    @PostMapping("/session/{id}/pick")
    public QuizService.StartOut pick(@CurrentUser LoginUser user, @PathVariable UUID id,
                                     @RequestBody(required = false) ScopeReq body) {
        return body == null ? quizService.pick(user.id(), id, null, List.of())
                : quizService.pick(user.id(), id, body.groupTag(), body.exclude());
    }

    @Operation(summary = "批改一题(以标准原文为基准,结构化评分),落库")
    @PostMapping("/session/{id}/grade")
    public QuizService.GradeOut grade(@CurrentUser LoginUser user, @PathVariable UUID id,
                                      @RequestBody @Valid GradeReq req) {
        quotaService.consume(user.id());
        return quizService.grade(user.id(), id, req.chunkId(), req.userAnswer());
    }

    @Operation(summary = "历史场次列表", description = "带每场的作答数与均分;answeredOnly=true 只返回答过题的场次")
    @GetMapping("/sessions")
    public List<SessionRow> sessions(@CurrentUser LoginUser user,
                                     @RequestParam(defaultValue = "50") int limit,
                                     @RequestParam(defaultValue = "false") boolean answeredOnly) {
        return quizService.sessions(user.id(), limit, answeredOnly);
    }

    @Operation(summary = "复盘总览:场次数 / 作答数 / 平均分 / 累计漏点")
    @GetMapping("/overview")
    public QuizDao.Overview overview(@CurrentUser LoginUser user) {
        return quizService.overview(user.id());
    }

    @Operation(summary = "某个场次的全部作答")
    @GetMapping("/session/{id}")
    public List<AnswerRow> session(@CurrentUser LoginUser user, @PathVariable UUID id) {
        return quizService.sessionAnswers(user.id(), id);
    }

    @Operation(summary = "薄弱聚合:按板块/类目统计平均分与漏点")
    @GetMapping("/weakness")
    public List<WeakRow> weakness(@CurrentUser LoginUser user) {
        return quizService.weakness(user.id());
    }

    @Operation(summary = "场次总结报告(LLM 汇总表现/薄弱点/复习建议)")
    @GetMapping("/session/{id}/report")
    public QuizService.ReportOut report(@CurrentUser LoginUser user, @PathVariable UUID id) {
        quotaService.consume(user.id());
        return quizService.report(user.id(), id);
    }

    // ------------------------------------------------------------------ 游客:无状态,不落库

    @Operation(summary = "抽题(不落库)", description = "游客用。本场出过的题全部放进 exclude")
    @PostMapping("/pick")
    public QuizService.StartOut pickFree(@RequestBody(required = false) ScopeReq body) {
        return body == null ? quizService.pickFree(null, List.of())
                : quizService.pickFree(body.groupTag(), body.exclude());
    }

    @Operation(summary = "批改一题(不落库)", description = "游客用。返回里没有 answerId")
    @PostMapping("/grade")
    public QuizService.GradeOut gradeFree(@RequestBody @Valid GradeReq req) {
        quotaService.consume(null);
        return quizService.gradeFree(req.chunkId(), req.userAnswer());
    }

    @Operation(summary = "本场总结(不落库)", description = "游客用。场次没存,由前端把本场每题的批改结果传回来")
    @PostMapping("/report")
    public QuizService.ReportOut reportFree(@RequestBody @Valid ReportReq req) {
        quotaService.consume(null);
        return quizService.reportFree(req.answers());
    }
}
