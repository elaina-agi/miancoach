package com.autumn.rag.dao;

import com.autumn.rag.corpus.MdParser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 评测 golden query 集(见 V3__eval_query.sql)。 */
@Repository
public class EvalQueryDao {

    private final JdbcTemplate jdbc;

    public EvalQueryDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(long id, String kind, String query, String targetQuestion) {}

    public void insert(String kind, String query, String targetQuestion, String note) {
        jdbc.update("INSERT INTO eval_query(kind, query, target_question, note) VALUES (?,?,?,?) "
                        + "ON CONFLICT (kind, query) DO NOTHING",
                kind, query, targetQuestion, note);
    }

    public void clear() {
        jdbc.update("DELETE FROM eval_query");
    }

    /** 只清某一类(kind=null 清全部)。 */
    public void clear(String kind) {
        if (kind == null) {
            jdbc.update("DELETE FROM eval_query");
        } else {
            jdbc.update("DELETE FROM eval_query WHERE kind = ?", kind);
        }
    }

    public int count(String kind) {
        Integer c = kind == null
                ? jdbc.queryForObject("SELECT count(*) FROM eval_query", Integer.class)
                : jdbc.queryForObject("SELECT count(*) FROM eval_query WHERE kind = ?", Integer.class, kind);
        return c == null ? 0 : c;
    }

    public List<Row> list(String kind) {
        return jdbc.query(
                "SELECT id, kind, query, target_question FROM eval_query "
                        + "WHERE (?::text IS NULL OR kind = ?) ORDER BY id",
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("kind"),
                        rs.getString("query"), rs.getString("target_question")),
                kind, kind);
    }

    /** 按目标题干解析当前 chunk_id(多页同题取全部;题干已随重灌变化则解析失败返回空)。 */
    public List<Long> resolveTarget(String targetQuestion) {
        String key = MdParser.normalizeKey(targetQuestion);
        return jdbc.queryForList("SELECT id FROM chunk WHERE question_key = ?", Long.class, key);
    }
}
