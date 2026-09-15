package com.autumn.rag.web;

import com.autumn.rag.service.EvalService;
import com.autumn.rag.service.GoldenQueryService;
import com.autumn.rag.service.IngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理", description = "索引管理:全量索引 + 检索评测")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final IngestService ingestService;
    private final EvalService evalService;
    private final GoldenQueryService goldenQueryService;

    public AdminController(IngestService ingestService, EvalService evalService,
                           GoldenQueryService goldenQueryService) {
        this.ingestService = ingestService;
        this.evalService = evalService;
        this.goldenQueryService = goldenQueryService;
    }

    @Operation(summary = "全量重灌索引(corpus md → chunk → embedding → pgvector)", description = "幂等,先清后插;耗时视题量而定")
    @PostMapping("/reindex")
    public IngestService.ReindexResult reindex() {
        return ingestService.reindex();
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
