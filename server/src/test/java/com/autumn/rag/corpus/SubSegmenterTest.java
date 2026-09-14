package com.autumn.rag.corpus;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 子段切分规则:长度门槛 / 大小约束 / 代码围栏原子性。 */
class SubSegmenterTest {

    @Test
    void 短答案不切() {
        String content = "这是答案。".repeat(30); // 90 字符 < 500
        assertTrue(SubSegmenter.segment(content).isEmpty());
    }

    @Test
    void 长答案切成目标大小的段且不超上限() {
        // 10 段、每段约 120 字符的段落,总 ~1200 字符 → 应切 2~3 段
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            sb.append("第").append(i).append("段内容。").append("八股文正文细节说明。".repeat(12)).append("\n\n");
        }
        List<SubSegmenter.Segment> segs = SubSegmenter.segment(sb.toString());
        assertTrue(segs.size() >= 2, "应切成多段,实际: " + segs.size());
        for (SubSegmenter.Segment s : segs) {
            assertTrue(s.content().length() <= 900 + 50, "单段超上限: " + s.content().length());
            assertTrue(s.content().length() >= 100, "出现碎段: " + s.content().length());
        }
    }

    @Test
    void 代码围栏是原子块_围栏内空行不切段() {
        StringBuilder sb = new StringBuilder();
        sb.append("开头说明文字。".repeat(60)).append("\n\n"); // ~420 字符
        sb.append("```java\npublic class A {\n\n    void m() {\n        // 空行在围栏里\n    }\n\n}\n```\n\n");
        sb.append("结尾说明文字。".repeat(60)); // ~420 字符
        List<SubSegmenter.Segment> segs = SubSegmenter.segment(sb.toString());
        // 围栏(~90 字符)要么单独成段,要么与相邻段落打包,但绝不能被从中间切开
        boolean fenceIntact = segs.stream().anyMatch(s ->
                s.content().contains("```java") && s.content().contains("```")
                && s.content().indexOf("```java") < s.content().lastIndexOf("```"));
        assertTrue(fenceIntact, "存在完整的 java 围栏块");
        long openFences = segs.stream().filter(s ->
                s.content().lines().filter(l -> l.strip().startsWith("```")).count() % 2 != 0).count();
        assertEquals(0, openFences, "不应出现未闭合围栏的段");
    }

    @Test
    void 超长单块按行硬切() {
        // 一个 ~2000 字符的巨段落(无空行),应硬切成 ≤900 的多段,行保持完整
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("这是一行很长的正文内容,讲述某个机制的第").append(i).append("个要点。\n");
        }
        List<SubSegmenter.Segment> segs = SubSegmenter.segment(sb.toString());
        assertTrue(segs.size() >= 2, "巨段落应被硬切");
        for (SubSegmenter.Segment s : segs) {
            assertTrue(s.content().length() <= 950, "硬切段仍超上限: " + s.content().length());
        }
    }
}
