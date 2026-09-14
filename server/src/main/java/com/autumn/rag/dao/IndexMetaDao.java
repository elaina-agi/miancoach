package com.autumn.rag.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

/** index_meta 单行表的读写(记录向量模型指纹)。 */
@Repository
public class IndexMetaDao {

    private final JdbcTemplate jdbc;

    public IndexMetaDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Meta(String embedModel, int embedDims, String embedProvider,
                       int chunkCount, String chunking, OffsetDateTime indexedAt) {}

    public void write(String embedModel, int embedDims, String embedProvider, int chunkCount, String chunking) {
        jdbc.update("INSERT INTO index_meta(id, embed_model, embed_dims, embed_provider, chunk_count, chunking, indexed_at) "
                        + "VALUES (1, ?, ?, ?, ?, ?, now()) "
                        + "ON CONFLICT (id) DO UPDATE SET "
                        + "embed_model = EXCLUDED.embed_model, "
                        + "embed_dims = EXCLUDED.embed_dims, "
                        + "embed_provider = EXCLUDED.embed_provider, "
                        + "chunk_count = EXCLUDED.chunk_count, "
                        + "chunking = EXCLUDED.chunking, "
                        + "indexed_at = now()",
                embedModel, embedDims, embedProvider, chunkCount, chunking);
    }

    public Meta read() {
        return jdbc.query("SELECT embed_model, embed_dims, embed_provider, chunk_count, chunking, indexed_at "
                        + "FROM index_meta WHERE id = 1",
                rs -> rs.next() ? new Meta(
                        rs.getString("embed_model"), rs.getInt("embed_dims"),
                        rs.getString("embed_provider"), rs.getInt("chunk_count"),
                        rs.getString("chunking"),
                        rs.getObject("indexed_at", OffsetDateTime.class)) : null);
    }
}
