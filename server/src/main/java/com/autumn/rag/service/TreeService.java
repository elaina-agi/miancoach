package com.autumn.rag.service;

import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.TreeRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 知识库树:板块 → 页 → (类目 →) 题目。叶子=可点击的一道题。 */
@Service
public class TreeService {

    private final CorpusDao dao;

    public TreeService(CorpusDao dao) {
        this.dao = dao;
    }

    public record QNode(long id, String question) {}

    public record CatNode(String category, List<QNode> questions) {}

    public record DocNode(String slug, String title, String url, List<CatNode> categories) {}

    public record GroupNode(String group, List<DocNode> docs) {}

    public List<GroupNode> tree() {
        List<TreeRow> rows = dao.treeRows();
        Map<String, List<DocNode>> groups = new LinkedHashMap<>();
        for (TreeRow r : rows) {
            List<DocNode> docs = groups.computeIfAbsent(r.group(), k -> new ArrayList<>());
            DocNode doc = findDoc(docs, r.slug(), r.title(), r.url());
            CatNode cat = findCat(doc, r.category());
            cat.questions().add(new QNode(r.chunkId(), r.question()));
        }
        return groups.entrySet().stream()
                .map(e -> new GroupNode(e.getKey(), e.getValue()))
                .toList();
    }

    private static DocNode findDoc(List<DocNode> docs, String slug, String title, String url) {
        for (DocNode d : docs) {
            if (d.slug().equals(slug)) {
                return d;
            }
        }
        DocNode d = new DocNode(slug, title, url, new ArrayList<>());
        docs.add(d);
        return d;
    }

    private static CatNode findCat(DocNode doc, String category) {
        for (CatNode c : doc.categories()) {
            if (java.util.Objects.equals(c.category(), category)) {
                return c;
            }
        }
        CatNode c = new CatNode(category, new ArrayList<>());
        doc.categories().add(c);
        return c;
    }
}
