package com.autumn.rag.dao;

import com.autumn.rag.corpus.MdParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 无数据库的 SQL/调用顺序回归;真实语义由 opt-in FavoriteReindexIntegrationTest 覆盖。 */
class CorpusReindexDaoTest {
    private JdbcTemplate jdbc;
    private CorpusDao dao;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        dao = new CorpusDao(jdbc);
    }

    private void dims(int parent, int child) {
        when(jdbc.queryForObject("SELECT atttypmod FROM pg_attribute WHERE attrelid = 'chunk'::regclass AND attname = 'embedding'",
                Integer.class)).thenReturn(parent);
        when(jdbc.queryForObject("SELECT atttypmod FROM pg_attribute WHERE attrelid = 'chunk_seg'::regclass AND attname = 'embedding'",
                Integer.class)).thenReturn(child);
    }

    @Test
    void 同维度不清向量也不执行DDL() {
        dims(8, 8);
        dao.ensureEmbeddingDims(8);
        verify(jdbc, never()).execute(anyString());
        verify(jdbc, never()).update(anyString());
    }

    @Test
    void 换维度必须先清非空旧向量再改列() {
        dims(8, 8);
        dao.ensureEmbeddingDims(16);
        var order = inOrder(jdbc);
        order.verify(jdbc).execute("DROP INDEX IF EXISTS idx_chunk_embedding");
        order.verify(jdbc).update("UPDATE chunk SET embedding = NULL WHERE embedding IS NOT NULL");
        order.verify(jdbc).execute("ALTER TABLE chunk ALTER COLUMN embedding TYPE vector(16)");
        order.verify(jdbc).execute("CREATE INDEX IF NOT EXISTS idx_chunk_embedding ON chunk USING hnsw (embedding vector_cosine_ops)");
        order.verify(jdbc).execute("ALTER TABLE chunk_seg ALTER COLUMN embedding TYPE vector(16)");
        verify(jdbc, never()).update("DELETE FROM chunk");
    }

    @Test
    void 只修复子段维度时不触碰父题() {
        dims(8, 16);
        dao.ensureEmbeddingDims(8);
        verify(jdbc).execute("ALTER TABLE chunk_seg ALTER COLUMN embedding TYPE vector(8)");
        verify(jdbc, never()).update(anyString());
        verify(jdbc, never()).execute("DROP INDEX IF EXISTS idx_chunk_embedding");
    }

    @Test
    void 删除缺失题目用保留数组并拒绝空集合() {
        assertThrows(IllegalArgumentException.class, () -> dao.deleteMissingChunks(List.of()));
        assertThrows(IllegalArgumentException.class, () -> dao.deleteMissingDocs(List.of()));
        verifyNoInteractions(jdbc);
        dao.deleteMissingChunks(List.of(1L, 2L));
        dao.deleteMissingDocs(List.of(3L));
        verify(jdbc).update(eq("DELETE FROM chunk WHERE NOT (id = ANY (?))"), any(SqlArrayValue.class));
        verify(jdbc).update(eq("DELETE FROM doc WHERE NOT (id = ANY (?))"), any(SqlArrayValue.class));
    }

    @Test
    void 重建锁禁止在自动提交连接上执行() {
        assertThrows(IllegalStateException.class, dao::tryReindexLock);
        verifyNoInteractions(jdbc);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            when(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, 0x4241475557454E52L)).thenReturn(true);
            assertTrue(dao.tryReindexLock());
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void 文档题目都是唯一键upsert且只返回数据库的ID() {
        var p = new MdParser.PageMeta("java", "jvm", "JVM", "", "", "test", "jvm.md");
        var q = new MdParser.Section("基础", "A", "a", "更新答案");
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(42L);
        assertEquals(42L, dao.insertDoc(p));
        assertEquals(42L, dao.insertChunk(42, q, new float[]{1, 0}));
        verify(jdbc).queryForObject(contains("ON CONFLICT (group_tag, slug) DO UPDATE SET"), eq(Long.class), any(Object[].class));
        verify(jdbc).queryForObject(contains("ON CONFLICT (doc_id, question) DO UPDATE SET"), eq(Long.class), any(Object[].class));
        verify(jdbc, never()).update(anyString());
    }
}
