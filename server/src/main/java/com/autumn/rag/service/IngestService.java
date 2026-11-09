package com.autumn.rag.service;

import com.autumn.rag.common.ConflictException;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 全量向量化:文档/父题按业务唯一键 upsert 保留 ID,子段索引重新生成。
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

    /** 进度回调:每向量化完一批调一次。阶段一是"题目",阶段二是"子段",各自从 0 数到 total。 */
    @FunctionalInterface
    public interface Progress {
        Progress NONE = (phase, done, total) -> { };

        void update(String phase, int done, int total);
    }

    /**
     * 整个过程在一个事务里:中途失败(比如向量接口挂了)全部回滚,库里还是旧索引,不会留下半套。
     * 普通重建由 MVCC 让读取仍看到旧数据;写入和外键操作可能等待,不承诺所有请求无等待。
     * 换向量维度时 ALTER 会锁表,重建结束前相关查询也可能等待。
     */
    @Transactional
    public ReindexResult reindex(Progress progress) {
        // 跨实例的非阻塞事务锁,提交/回滚自动释放。
        if (!dao.tryReindexLock()) throw new ConflictException("已有题库重建任务正在执行");
        // 先验证输入再修改数据库,避免空目录/重复题干被误当成正常删除发布。
        List<ParsedPage> pages = scanner.loadAll();
        validatePages(pages);
        int dims = embed.dims();
        if (dims <= 0) throw new IllegalArgumentException("向量维度必须为正数");
        dao.clearSegs();
        dao.ensureEmbeddingDims(dims);

        // 更新已有父题而不是删除它;仅可重建的子段索引全量替换。
        Set<Long> docIds = new LinkedHashSet<>();
        Set<Long> chunkIds = new LinkedHashSet<>();
        int batchSize = Math.max(1, props.embed().batchSize());
        int totalSections = pages.stream().mapToInt(p -> p.sections().size()).sum();
        progress.update("题目", 0, totalSections);
        int chunkCount = 0;
        List<PendingSeg> pending = new ArrayList<>();
        for (ParsedPage page : pages) {
            long docId = dao.insertDoc(page.meta());
            docIds.add(docId);
            List<Section> sections = page.sections();
            for (int i = 0; i < sections.size(); i += batchSize) {
                List<Section> batch = sections.subList(i, Math.min(i + batchSize, sections.size()));
                List<String> texts = batch.stream().map(IngestService::embedText).toList();
                List<float[]> vectors = embed.embed(texts);
                for (int j = 0; j < batch.size(); j++) {
                    Section s = batch.get(j);
                    long chunkId = dao.insertChunk(docId, s, vectors.get(j));
                    chunkIds.add(chunkId);
                    chunkCount++;
                    List<SubSegmenter.Segment> segs = SubSegmenter.segment(s.content());
                    for (int k = 0; k < segs.size(); k++) {
                        pending.add(new PendingSeg(chunkId, k,
                                segEmbedText(s.question(), segs.get(k).content()), segs.get(k).content()));
                    }
                }
                progress.update("题目", chunkCount, totalSections);
            }
        }

        // 3) 子段批量向量化入库(同 batchSize 节流)
        progress.update("子段", 0, pending.size());
        for (int i = 0; i < pending.size(); i += batchSize) {
            List<PendingSeg> batch = pending.subList(i, Math.min(i + batchSize, pending.size()));
            List<float[]> vectors = embed.embed(batch.stream().map(PendingSeg::embedText).toList());
            for (int j = 0; j < batch.size(); j++) {
                PendingSeg p = batch.get(j);
                dao.insertSeg(p.chunkId(), p.seq(), p.content(), vectors.get(j));
            }
            progress.update("子段", i + batch.size(), pending.size());
        }

        // 仅真正消失的源题被删除(其收藏级联清理),同题收藏和作答引用继续有效。
        dao.deleteMissingChunks(chunkIds);
        dao.deleteMissingDocs(docIds);
        // 4) 记指纹(模型 + 维度 + 分块方案)
        indexMetaDao.write(embed.model(), embed.dims(),
                embed.isReal() ? "openai" : "mock", chunkCount, SubSegmenter.SCHEME);

        return new ReindexResult(pages.size(), chunkCount, pending.size(), dao.groupChunkCounts(),
                embed.model(), embed.dims(), embed.isReal());
    }

    private record DocKey(String group, String slug) {}

    private static void validatePages(List<ParsedPage> pages) {
        if (pages == null || pages.isEmpty()) throw new IllegalArgumentException("语料为空,拒绝重建以免删除现有题库");
        Set<DocKey> docs = new HashSet<>();
        for (ParsedPage page : pages) {
            if (page == null || page.meta() == null || page.meta().group() == null || page.meta().group().isBlank()
                    || page.meta().slug() == null || page.meta().slug().isBlank()) {
                throw new IllegalArgumentException("语料文档缺少板块或 slug");
            }
            var key = new DocKey(page.meta().group(), page.meta().slug());
            if (!docs.add(key)) throw new IllegalArgumentException("重复语料文档: " + key);
            if (page.sections() == null || page.sections().isEmpty()) {
                throw new IllegalArgumentException("文档没有可用题目,请检查抓取/解析结果: " + key);
            }
            Set<String> questions = new HashSet<>();
            for (Section s : page.sections()) {
                if (s == null || s.question() == null || s.question().isBlank() || s.content() == null || s.content().isBlank()) {
                    throw new IllegalArgumentException("文档含空题干或答案: " + key);
                }
                if (!questions.add(s.question())) throw new IllegalArgumentException("同一文档含重复题干: " + s.question());
            }
        }
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
