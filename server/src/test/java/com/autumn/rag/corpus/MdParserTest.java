package com.autumn.rag.corpus;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 切题规则:叶子级标题 = 一道题;两套标题结构(h2类目+h3题 / h2直接当题)都要兼容。 */
class MdParserTest {

    private static final Path CORPUS = Path.of("../corpus");

    @Test
    void h2类目_h3题_结构切题正确() throws Exception {
        Path f = Path.of("src/test/resources/fixtures/h3-page.md").toAbsolutePath();
        MdParser.ParsedPage page = MdParser.parse(f, "java");
        assertEquals(3, page.sections().size());
        assertEquals("类目一", page.sections().get(0).category());
        assertEquals("第一题", page.sections().get(0).question());
        // 精确比对:正文止于下一条标题之前,不能把下一题 / 下一类目的标题带进来
        assertEquals("第一题的答案内容。", page.sections().get(0).content());
        assertEquals("第二题的答案内容。", page.sections().get(1).content());
        assertEquals("类目二", page.sections().get(2).category());
        // 容器类目本身不入库
        assertFalse(page.sections().stream().anyMatch(s -> s.question().equals("类目一")));
    }

    @Test
    void h2直接当题_无类目() throws Exception {
        Path f = Path.of("src/test/resources/fixtures/h2-page.md").toAbsolutePath();
        MdParser.ParsedPage page = MdParser.parse(f, "tools");
        assertEquals(1, page.sections().size());
        assertEquals(null, page.sections().get(0).category());
        assertEquals("Docker 是什么?", page.sections().get(0).question());
    }

    @Test
    void 围栏内的井号不认作标题() throws Exception {
        Path f = Path.of("src/test/resources/fixtures/fence-page.md").toAbsolutePath();
        MdParser.ParsedPage page = MdParser.parse(f, "java");
        // 围栏里的 "# 注释" 不能把题切断
        assertEquals(1, page.sections().size());
        assertTrue(page.sections().get(0).content().contains("# 这是代码里的注释"));
    }

    @Test
    void 归一化键_去标点空白_小写() {
        // 只保留字母/数字(汉字是 letter,保留);标点与空白全去掉
        assertEquals("hashmap的底层原理", MdParser.normalizeKey("HashMap 的底层原理?"));
        assertEquals("abc123", MdParser.normalizeKey("A-B_C 123!"));
    }

    @Test
    void 真实语料全量可解析且题数正确() throws Exception {
        // corpus/ 不入库(版权),刚 clone 下来还没跑爬虫时没有语料:跳过而不是失败
        assumeTrue(Files.isRegularFile(CORPUS.resolve("interview/database/redis.md")),
                "corpus/ 不存在,先运行 python -m crawler.main 生成语料");
        // 用真实语料抽查:redis.md 68 题 / systemdesign.md 14 题(README 数字),防解析回归
        List<MdParser.Section> redis = MdParser.parse(CORPUS.resolve("interview/database/redis.md").toAbsolutePath(), "database").sections();
        List<MdParser.Section> sd = MdParser.parse(CORPUS.resolve("interview/architecture/systemdesign.md").toAbsolutePath(), "architecture").sections();
        assertEquals(48, redis.size(), "redis.md 切题数");
        assertEquals(14, sd.size(), "systemdesign.md 切题数");
        for (MdParser.Section s : redis) {
            String last = s.content().lines().reduce((a, b) -> b).orElse("");
            assertFalse(last.matches("#{1,6}\\s.*"), "「" + s.question() + "」的答案末尾带了标题行: " + last);
        }
    }
}
