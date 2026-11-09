package com.autumn.rag.service;

import com.autumn.rag.common.ConflictException;
import com.autumn.rag.config.AppProperties;
import com.autumn.rag.corpus.CorpusScanner;
import com.autumn.rag.corpus.MdParser;
import com.autumn.rag.corpus.SubSegmenter;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.IndexMetaDao;
import com.autumn.rag.llm.EmbeddingProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IngestServiceTest {
    private final CorpusDao dao = mock(CorpusDao.class);
    private final CorpusScanner scanner = mock(CorpusScanner.class);
    private final IndexMetaDao meta = mock(IndexMetaDao.class);
    private final EmbeddingProvider embed = mock(EmbeddingProvider.class);
    private final AppProperties props = new AppProperties(null, null,
            new AppProperties.Embed("mock", "", "", "test", 8, 2, false, 10, 10),
            null, null, null, null, null);
    private final IngestService service = new IngestService(props, scanner, dao, meta, embed);

    private MdParser.Section q(String name, String answer) {
        return new MdParser.Section("基础", name, MdParser.normalizeKey(name), answer);
    }
    private MdParser.ParsedPage page(MdParser.Section... qs) {
        return new MdParser.ParsedPage(new MdParser.PageMeta("java", "jvm", "JVM", "", "", "test", "jvm.md"), List.of(qs));
    }

    @BeforeEach
    void setup() {
        when(dao.tryReindexLock()).thenReturn(true);
        when(embed.dims()).thenReturn(8);
        when(embed.model()).thenReturn("mock-test");
        when(embed.embed(anyList())).thenAnswer(invocation -> {
            List<String> texts = invocation.getArgument(0);
            return texts.stream().map(t -> new float[8]).toList();
        });
        when(dao.insertDoc(any())).thenReturn(21L);
        when(dao.insertChunk(eq(21L), any(), any())).thenAnswer(i -> {
            MdParser.Section section = i.getArgument(1);
            return section.question().equals("A") ? 101L : 102L;
        });
        when(dao.groupChunkCounts()).thenReturn(Map.of("java", 2));
    }

    @Test
    void 重建按返回的稳定ID保留父题且先切换子段后删缺失() {
        when(scanner.loadAll()).thenReturn(List.of(page(q("A", "答案 A"), q("B", "答案 B"))));
        var result = service.reindex(IngestService.Progress.NONE);
        assertEquals(2, result.chunks());
        var order = inOrder(dao, scanner, embed, meta);
        order.verify(dao).tryReindexLock();
        order.verify(scanner).loadAll();
        order.verify(embed).dims();
        order.verify(dao).clearSegs();
        order.verify(dao).ensureEmbeddingDims(8);
        order.verify(dao).insertDoc(any());
        order.verify(embed).embed(anyList());
        order.verify(dao, times(2)).insertChunk(eq(21L), any(), any());
        order.verify(dao).deleteMissingChunks(Set.of(101L, 102L));
        order.verify(dao).deleteMissingDocs(Set.of(21L));
        order.verify(meta).write("mock-test", 8, "mock", 2, SubSegmenter.SCHEME);
        verify(dao, never()).wipe();
    }

    @Test
    void 修改答案和调换顺序仍使用原题ID() {
        when(scanner.loadAll()).thenReturn(List.of(page(q("B", "新答案"), q("A", "更新后的答案"))));
        service.reindex(IngestService.Progress.NONE);
        verify(dao).insertChunk(eq(21L), eq(q("A", "更新后的答案")), any());
        verify(dao).insertChunk(eq(21L), eq(q("B", "新答案")), any());
        verify(dao).deleteMissingChunks(Set.of(101L, 102L));
        verify(dao, never()).wipe();
    }

    @Test
    void 子段命中的是原父题ID() {
        when(scanner.loadAll()).thenReturn(List.of(page(q("A", "长答案的某一行。\n".repeat(120)))));
        var result = service.reindex(IngestService.Progress.NONE);
        assertTrue(result.segs() > 0);
        verify(dao, atLeastOnce()).insertSeg(eq(101L), anyInt(), anyString(), any());
        verify(dao).deleteMissingChunks(Set.of(101L));
    }

    @Test
    void 并发重建拿不到锁时不扫描也不写数据() {
        when(dao.tryReindexLock()).thenReturn(false);
        assertThrows(ConflictException.class, () -> service.reindex(IngestService.Progress.NONE));
        verifyNoInteractions(scanner, embed, meta);
        verify(dao, never()).clearSegs();
    }

    @Test
    void 空语料和空页面在清理之前被拒绝() {
        when(scanner.loadAll()).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class, () -> service.reindex(IngestService.Progress.NONE));
        when(scanner.loadAll()).thenReturn(List.of(page()));
        assertThrows(IllegalArgumentException.class, () -> service.reindex(IngestService.Progress.NONE));
        verify(dao, never()).clearSegs();
        verifyNoInteractions(meta);
    }

    @Test
    void 重复文档和同文档重复题干被拒绝() {
        var one = page(q("A", "答案"));
        when(scanner.loadAll()).thenReturn(List.of(one, one));
        assertThrows(IllegalArgumentException.class, () -> service.reindex(IngestService.Progress.NONE));
        when(scanner.loadAll()).thenReturn(List.of(page(q("A", "答案1"), q("A", "答案2"))));
        assertThrows(IllegalArgumentException.class, () -> service.reindex(IngestService.Progress.NONE));
        verify(dao, never()).clearSegs();
        verifyNoInteractions(meta);
    }

    @Test
    void 向量服务失败不清理父题不发布新指纹() {
        when(scanner.loadAll()).thenReturn(List.of(page(q("A", "答案"))));
        when(embed.embed(anyList())).thenThrow(new IllegalStateException("模拟故障"));
        assertThrows(IllegalStateException.class, () -> service.reindex(IngestService.Progress.NONE));
        verify(dao, never()).deleteMissingChunks(any());
        verify(dao, never()).deleteMissingDocs(any());
        verify(dao, never()).wipe();
        verifyNoInteractions(meta);
    }

    @Test
    void 非法维度在删子段之前被拒绝() {
        when(scanner.loadAll()).thenReturn(List.of(page(q("A", "答案"))));
        when(embed.dims()).thenReturn(0);
        assertThrows(IllegalArgumentException.class, () -> service.reindex(IngestService.Progress.NONE));
        verify(dao, never()).clearSegs();
        verify(dao, never()).ensureEmbeddingDims(anyInt());
    }
}
