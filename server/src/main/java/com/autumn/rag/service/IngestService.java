package com.autumn.rag.service;

import com.autumn.rag.config.AppProperties;
import com.autumn.rag.corpus.CorpusScanner;
import com.autumn.rag.corpus.MdParser.ParsedPage;
import com.autumn.rag.corpus.MdParser.Section;
import com.autumn.rag.corpus.SubSegmenter;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.IndexMetaDao;
import com.autumn.rag.llm.EmbeddingProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 全量索引:读 corpus md → 按题入库 → embedding 写 pgvector。幂等(先清后插)。
 * 结束后把"这批向量是谁算的、按什么方案切的"写进 index_meta(向量模型指纹),供检索前校验。
 *
 * parent-child:每道题(父块)入库后,长答案经 {@link SubSegmenter} 切出子段,
 * 子段文本 = 题干 + 子段正文(给子段向量补上题目语境),批量向量化后写 chunk_seg。
 * 检索时子段命中 → 升级到父题,生成端读父题全文。
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
    public record ReindexResult(int docs, int chunks, int segs, Map<String, Integer> groupChunks,
                                String embedModel, int embedDims, boolean embedReal) {}

    /** 待入库子段(所属父题已入库,拿到 chunkId 后排队等批量向量化)。 */
    private record PendingSeg(long chunkId, int seq, String embedText, String content) {}

    @Transactional
    public ReindexResult reindex() {
        // 1) 清库 + 让向量列维度跟随当前配置(换模型换维度时的必要动作)
        dao.wipe();
        dao.ensureEmbeddingDims(embed.dims());

        // 2) 逐页切题入库;长答案切子段,攒批向量化
        List<ParsedPage> pages = scanner.loadAll();
        int batchSize = Math.max(1, props.embed().batchSize());
        int chunkCount = 0;
        List<PendingSeg> pending = new ArrayList<>();
        for (ParsedPage page : pages) {
            long docId = dao.insertDoc(page.meta());
            List<Section> sections = page.sections();
            for (int i = 0; i < sections.size(); i += batchSize) {
                List<Section> batch = sections.subList(i, Math.min(i + batchSize, sections.size()));
                List<String> texts = batch.stream().map(IngestService::embedText).toList();
                List<float[]> vectors = embed.embed(texts);
                for (int j = 0; j < batch.size(); j++) {
                    Section s = batch.get(j);
                    long chunkId = dao.insertChunk(docId, s, vectors.get(j));
                    chunkCount++;
                    List<SubSegmenter.Segment> segs = SubSegmenter.segment(s.content());
                    for (int k = 0; k < segs.size(); k++) {
                        pending.add(new PendingSeg(chunkId, k,
                                segEmbedText(s.question(), segs.get(k).content()), segs.get(k).content()));
                    }
                }
            }
        }

        // 3) 子段批量向量化入库(同 batchSize 节流)
        for (int i = 0; i < pending.size(); i += batchSize) {
            List<PendingSeg> batch = pending.subList(i, Math.min(i + batchSize, pending.size()));
            List<float[]> vectors = embed.embed(batch.stream().map(PendingSeg::embedText).toList());
            for (int j = 0; j < batch.size(); j++) {
                PendingSeg p = batch.get(j);
                dao.insertSeg(p.chunkId(), p.seq(), p.content(), vectors.get(j));
            }
        }

        // 4) 记指纹(模型 + 维度 + 分块方案)
        indexMetaDao.write(embed.model(), embed.dims(),
                embed.isReal() ? "openai" : "mock", chunkCount, SubSegmenter.SCHEME);

        return new ReindexResult(pages.size(), chunkCount, pending.size(), dao.groupChunkCounts(),
                embed.model(), embed.dims(), embed.isReal());
    }

    private static String embedText(Section s) {
        String text = s.question() + "\n" + s.content();
        return text.length() > EMBED_TEXT_LIMIT ? text.substring(0, EMBED_TEXT_LIMIT) : text;
    }

    /** 子段的向量文本:题干做前缀,防止答案片段脱离题目语境后语义漂移。 */
    private static String segEmbedText(String question, String segContent) {
        String text = question + "\n" + segContent;
        return text.length() > EMBED_TEXT_LIMIT ? text.substring(0, EMBED_TEXT_LIMIT) : text;
    }
}
