package com.autumn.rag.web;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.service.TreeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@Tag(name = "知识库", description = "板块/题目浏览")
@RestController
@RequestMapping("/api/corpus")
public class CorpusController {

    private final TreeService treeService;
    private final CorpusDao dao;

    public CorpusController(TreeService treeService, CorpusDao dao) {
        this.treeService = treeService;
        this.dao = dao;
    }

    @Operation(summary = "知识库树:板块→页→类目→题目")
    @GetMapping("/tree")
    public java.util.List<TreeService.GroupNode> tree() {
        return treeService.tree();
    }

    @Operation(summary = "各板块题数统计")
    @GetMapping("/groups")
    public Map<String, Integer> groups() {
        return dao.groupChunkCounts();
    }

    @Operation(summary = "单题原文(markdown)")
    @GetMapping("/chunk/{id}")
    public CorpusDao.ChunkFull chunk(@PathVariable long id) {
        CorpusDao.ChunkFull c = dao.getChunk(id);
        if (c == null) {
            throw new NotFoundException("题目不存在: " + id);
        }
        return c;
    }
}
