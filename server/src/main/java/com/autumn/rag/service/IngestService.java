package com.autumn.rag.service;

import com.autumn.rag.config.AppProperties;
import com.autumn.rag.corpus.CorpusScanner;
import com.autumn.rag.corpus.MdParser.ParsedPage;
import com.autumn.rag.corpus.MdParser.Section;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.IndexMetaDao;
import com.autumn.rag.llm.EmbeddingProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 全量索引:读 corpus md → 按题入库 → embedding 写 pgvector。幂等(先清后插)。
 * 结束后把"这批向量是谁算的"写进 index_meta(向量模型指纹),供检索前校验。
 */
@Service
public class IngestService {

    private static final int EMBED_TEXT_LIMIT = 6000; // 给向量模型的文本上限(字符),超出截断

    private final AppProperties props;
    private final CorpusScanner scanner;
    private final CorpusDao dao;
    private final IndexMetaDao indexMetaDao;
    private final EmbeddingProvider embed;

    public IngestService(AppProperties props, CorpusScanner scanner, CorpusDao dao,
                         IndexMetaDao indexMetaDao, EmbeddingProvider embed) {
        this.props = props;
        this.scanner = scanner;
        this.dao = dao;
        this.indexMetaDao = indexMetaDao;
        this.embed = embed;
    }

    /** 入库结果,顺带当"索引即评测-总量校验"的依据。 */
    public record ReindexResult(int docs, int chunks, Map<String, Integer> groupChunks,
                                String embedModel, int embedDims, boolean embedReal) {}

    @Transactional
    public ReindexResult reindex() {
        // 1) 清库 + 让向量列维度跟随当前配置(换模型换维度时的必要动作)
        dao.wipe();
        dao.ensureEmbeddingDims(embed.dims());

        // 2) 逐页切题、批量算向量、入库
        List<ParsedPage> pages = scanner.loadAll();
        int batchSize = Math.max(1, props.embed().batchSize());
        int chunkCount = 0;
        for (ParsedPage page : pages) {
            long docId = dao.insertDoc(page.meta());
            List<Section> sections = page.sections();
            for (int i = 0; i < sections.size(); i += batchSize) {
                List<Section> batch = sections.subList(i, Math.min(i + batchSize, sections.size()));
                List<String> texts = batch.stream().map(IngestService::embedText).toList();
                List<float[]> vectors = embed.embed(texts);
                for (int j = 0; j < batch.size(); j++) {
                    dao.insertChunk(docId, batch.get(j), vectors.get(j));
                }
            }
            chunkCount += sections.size();
        }

        // 3) 记指纹
        indexMetaDao.write(embed.model(), embed.dims(),
                embed.isReal() ? "openai" : "mock", chunkCount);

        return new ReindexResult(pages.size(), chunkCount, dao.groupChunkCounts(),
                embed.model(), embed.dims(), embed.isReal());
    }

    private static String embedText(Section s) {
        String text = s.question() + "\n" + s.content();
        return text.length() > EMBED_TEXT_LIMIT ? text.substring(0, EMBED_TEXT_LIMIT) : text;
    }
}
