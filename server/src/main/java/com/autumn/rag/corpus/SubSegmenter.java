package com.autumn.rag.corpus;

import java.util.ArrayList;
import java.util.List;

/**
 * parent-child 分块的"子段"切分器:把一道题的长答案切成若干段(每段给向量端建索引),
 * 检索命中子段后回父题取全文。方案标识 {@link #SCHEME} 记入 index_meta,换方案必须重灌。
 *
 * 规则(针对本语料实测结构调整:答案内部没有更低级标题,但代码围栏多):
 *   - 答案 ≤ {@link #MIN_CONTENT_TO_SPLIT} 字符不切(题目级向量已足够,避免子段表无谓膨胀);
 *   - 先做"围栏感知"分段:``` 代码块是原子块(块内空行不切段),围栏外按空行分段;
 *   - 贪心把相邻段打包到 {@link #SEGMENT_TARGET} 字符左右,绝不混切代码块;
 *   - 单块超过 {@link #SEGMENT_MAX}(超长代码块/巨段落)按行硬切,行内不拆。
 * 硬切一个超长代码块会让块内出现"半个代码块"的子段,可接受:它只影响该子段自己的向量质量,
 * 生成端永远读父题全文,不会把残缺代码喂给模型。
 */
public final class SubSegmenter {

    /** 分块方案标识(记入 index_meta.chunking)。改切分规则时必须同步升版本并重灌。 */
    public static final String SCHEME = "parent-child-v1";

    static final int MIN_CONTENT_TO_SPLIT = 500;
    static final int SEGMENT_TARGET = 500;
    static final int SEGMENT_MAX = 900;

    /** 一个子段:heading 预留给未来"答案内部小标题",当前语料没有,恒为 null。 */
    public record Segment(String heading, String content) {}

    private SubSegmenter() {}

    /** 返回该答案应切的子段列表;不需要切(短答案)返回空表。 */
    public static List<Segment> segment(String content) {
        if (content == null || content.length() <= MIN_CONTENT_TO_SPLIT) {
            return List.of();
        }
        List<Segment> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String block : blocks(content)) {
            if (block.length() > SEGMENT_MAX) {
                flush(out, cur);
                for (String piece : hardSplit(block)) {
                    out.add(new Segment(null, piece));
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + block.length() + 2 > SEGMENT_TARGET) {
                flush(out, cur);
            }
            if (cur.length() > 0) {
                cur.append("\n\n");
            }
            cur.append(block);
        }
        flush(out, cur);
        return out;
    }

    /** 围栏感知分段:代码块整体是一个块;其余按空行分段。 */
    private static List<String> blocks(String content) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inFence = false;
        for (String line : content.split("\n", -1)) {
            String trimmed = line.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence;
                cur.append(line).append('\n');
                if (!inFence) { // 围栏闭合,整个代码块落成一个块
                    out.add(stripTail(cur));
                    cur.setLength(0);
                }
                continue;
            }
            if (!inFence && trimmed.isEmpty()) { // 围栏外的空行 = 段界
                if (cur.length() > 0) {
                    out.add(stripTail(cur));
                    cur.setLength(0);
                }
                continue;
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) {
            out.add(stripTail(cur));
        }
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    /** 超长块按行打包成 ≤ SEGMENT_MAX 的片段(行内不拆,代码行保持完整)。 */
    private static List<String> hardSplit(String block) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String line : block.split("\n", -1)) {
            if (cur.length() + line.length() + 1 > SEGMENT_MAX && cur.length() > 0) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            }
            cur.append(line).append('\n');
        }
        if (cur.length() > 0) {
            out.add(cur.toString().strip());
        }
        return out.stream().filter(s -> !s.isBlank()).toList();
    }

    private static void flush(List<Segment> out, StringBuilder cur) {
        if (cur.length() > 0) {
            String s = cur.toString().strip();
            if (!s.isEmpty()) {
                out.add(new Segment(null, s));
            }
            cur.setLength(0);
        }
    }

    private static String stripTail(StringBuilder sb) {
        return sb.toString().stripTrailing();
    }
}
