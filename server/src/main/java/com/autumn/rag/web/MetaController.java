package com.autumn.rag.web;

import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.llm.ChatProvider;
import com.autumn.rag.llm.EmbeddingProvider;
import com.autumn.rag.service.IndexMetaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "元信息", description = "前端壳状态 + 索引健康")
@RestController
@RequestMapping("/api")
public class MetaController {

    private final ChatProvider chat;
    private final EmbeddingProvider embed;
    private final IndexMetaService indexMeta;
    private final CorpusDao dao;

    public MetaController(ChatProvider chat, EmbeddingProvider embed,
                          IndexMetaService indexMeta, CorpusDao dao) {
        this.chat = chat;
        this.embed = embed;
        this.indexMeta = indexMeta;
        this.dao = dao;
    }

    public record Meta(
            String status,
            boolean chatReal, String chatModel,
            boolean embedReal, String embedModel, int embedDims,
            int totalChunks, int totalSegs, int docs,
            IndexMetaService.IndexStatus index) {}

    @Operation(summary = "服务状态 + 两端模型模式 + 题量/子段量 + 向量模型指纹校验")
    @GetMapping("/meta")
    public Meta meta() {
        return new Meta(
                "ok",
                chat.isReal(), chat.name(),
                embed.isReal(), embed.model(), embed.dims(),
                dao.countChunks(), dao.countSegs(), dao.countDocs(),
                indexMeta.status());
    }
}
