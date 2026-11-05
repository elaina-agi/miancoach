package com.autumn.rag.web;

import com.autumn.rag.service.EvalService;
import com.autumn.rag.service.GoldenQueryService;
import com.autumn.rag.service.ReindexJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/admin/** 统一由 AdminInterceptor 校验管理员身份,这里的方法不用再各自判断。 */
@Tag(name = "管理", description = "仅管理员:全量索引 + 检索评测 + 用户管理")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ReindexJobService reindexJob;
    private final EvalService evalService;
    private final GoldenQueryService goldenQueryService;

    public AdminController(ReindexJobService reindexJob, EvalService evalService,
                           GoldenQueryService goldenQueryService) {
        this.reindexJob = reindexJob;
        this.evalService = evalService;
        this.goldenQueryService = goldenQueryService;
    }

    @Operation(summary = "开始全量重建索引(corpus md → chunk → embedding → pgvector)",
            description = "后台执行,立刻返回 202;进度用 GET /api/admin/reindex 查。已经在跑时 409。"
                    + "先清后插,题目 id 会全部重新生成")
    @PostMapping("/reindex")
    public ResponseEntity<ReindexJobService.Status> reindex() {
        return ResponseEntity.accepted().body(reindexJob.start());
    }

    @Operation(summary = "重建索引的进度 / 结果", description = "state:IDLE 本次启动后没跑过 / RUNNING / SUCCEEDED / FAILED")
    @GetMapping("/reindex")
    public ReindexJobService.Status reindexStatus() {
        return reindexJob.status();
    }

    @Operation(summary = "构建 golden 评测集(LLM 改写题干 + 细节探针 + 域外负样本)",
            description = "rewriteLimit=0 全量 574 题(每次 8 题批量调 LLM);probeCount 取最长的答案出细节追问;only=rewrite|probe|negative 只重建一类")
    @PostMapping("/eval/build")
    public GoldenQueryService.BuildResult buildEval(@RequestParam(defaultValue = "0") int rewriteLimit,
                                                    @RequestParam(defaultValue = "40") int probeCount,
                                                    @RequestParam(required = false) String only) {
        return goldenQueryService.build(rewriteLimit, probeCount, only);
    }

    @Operation(summary = "检索评测", description = "mode=self 自环回归(旧);mode=golden 去自环评测(recall@k/MRR/负样本拒答定标)")
    @GetMapping("/eval")
    public Object eval(@RequestParam(defaultValue = "20") int n,
                       @RequestParam(defaultValue = "5") int topN,
                       @RequestParam(defaultValue = "self") String mode,
                       @RequestParam(required = false) String kind,
                       @RequestParam(required = false, defaultValue = "-1") double rejectSim,
                       @RequestParam(required = false, defaultValue = "-1") double rejectRerank) {
        if ("golden".equalsIgnoreCase(mode)) {
            return evalService.golden(n, topN, kind, rejectSim, rejectRerank);
        }
        return evalService.selfRecall(n, topN);
    }
}
