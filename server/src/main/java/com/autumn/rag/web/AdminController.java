package com.autumn.rag.web;

import com.autumn.rag.service.EvalService;
import com.autumn.rag.service.IngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "管理", description = "索引管理:全量索引 + 检索自评")
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final IngestService ingestService;
    private final EvalService evalService;

    public AdminController(IngestService ingestService, EvalService evalService) {
        this.ingestService = ingestService;
        this.evalService = evalService;
    }

    @Operation(summary = "全量重灌索引(corpus md → chunk → embedding → pgvector)", description = "幂等,先清后插;耗时视题量而定")
    @PostMapping("/reindex")
    public IngestService.ReindexResult reindex() {
        return ingestService.reindex();
    }

    @Operation(summary = "检索自评:抽 n 题当 golden,报 exact/vector 的 recall@1/@k")
    @GetMapping("/eval")
    public EvalService.EvalOut eval(@RequestParam(defaultValue = "20") int n,
                                    @RequestParam(defaultValue = "5") int topK) {
        return evalService.selfRecall(n, topK);
    }
}
