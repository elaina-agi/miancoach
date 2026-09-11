package com.autumn.rag.service;

import com.autumn.rag.common.IndexMismatchException;
import com.autumn.rag.dao.IndexMetaDao;
import com.autumn.rag.llm.EmbeddingProvider;
import org.springframework.stereotype.Service;

/**
 * 向量模型指纹校验。
 *
 * 检索前调用 {@link #assertCompatible()}:若"建索引用的模型/维度"和"当前配置"不一致,
 * 直接抛错并告诉用户重新索引 —— 而不是拿着不在同一空间的向量闷头算相似度。
 * (同维度换不同模型是最危险的:不会报错,但结果全是噪声。)
 */
@Service
public class IndexMetaService {

    private final IndexMetaDao dao;
    private final EmbeddingProvider embed;

    public IndexMetaService(IndexMetaDao dao, EmbeddingProvider embed) {
        this.dao = dao;
        this.embed = embed;
    }

    public record IndexStatus(
            boolean indexed, boolean compatible,
            String indexedModel, Integer indexedDims, String indexedProvider, String indexedAt,
            String currentModel, int currentDims, boolean currentReal,
            String message) {}

    public IndexStatus status() {
        IndexMetaDao.Meta meta = dao.read();
        String curModel = embed.model();
        int curDims = embed.dims();
        if (meta == null) {
            return new IndexStatus(false, false, null, null, null, null,
                    curModel, curDims, embed.isReal(),
                    "尚未建立索引:请先 POST /api/admin/reindex");
        }
        boolean ok = meta.embedModel().equals(curModel) && meta.embedDims() == curDims;
        String msg = ok
                ? "索引与当前向量模型一致(" + curModel + " / " + curDims + "d)"
                : "索引是用「" + meta.embedModel() + " / " + meta.embedDims() + "d」建立的,"
                  + "而当前配置是「" + curModel + " / " + curDims + "d」。"
                  + "两者向量不在同一空间,检索结果不可信 —— 请重新索引:POST /api/admin/reindex";
        return new IndexStatus(true, ok,
                meta.embedModel(), meta.embedDims(), meta.embedProvider(),
                meta.indexedAt() == null ? null : meta.indexedAt().toString(),
                curModel, curDims, embed.isReal(), msg);
    }

    /** 不一致(或没索引)就抛错。检索前必调。 */
    public void assertCompatible() {
        IndexStatus s = status();
        if (!s.compatible()) {
            throw new IndexMismatchException(s.message());
        }
    }
}
