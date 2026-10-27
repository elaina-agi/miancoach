package com.autumn.rag.corpus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 解析一个语料 md:frontmatter 元信息 + 正文按标题层级切"题"。
 *
 * 结构兼容两种:
 *   java/database/mq/cap 页   h1标题 → h2类目 → h3题(叶子)
 *   systemdesign/tools 各页    h1标题 → h2题(叶子)
 * 规则:**叶子级标题 = 一道题**(该标题下没有更低级标题),category = 父级标题。
 */
public final class MdParser {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern FOOTER = Pattern.compile("公众号|加群|二维码|扫码|求职训练营|关注.+公众号");

    /** 页面元信息(frontmatter + 路径)。 */
    public record PageMeta(
            String group, String slug, String title, String url, String lastmod, String source, String mdFile) {}

    /** 一道题。 */
    public record Section(String category, String question, String questionKey, String content) {}

    public record ParsedPage(PageMeta meta, List<Section> sections) {}

    /** line:标题所在行号(0 起);正文从 line + 1 开始 */
    private record OutlineEntry(int level, String text, int line) {}

    private MdParser() {}

    // ------------------------------------------------------------------ 入口
    public static ParsedPage parse(Path file, String group) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读语料失败: " + file, e);
        }
        // CRLF 统一成 LF:否则行尾的 \r 让标题正则整行匹配失败,整页切出 0 题(Windows 上编辑过的 md 会这样)
        raw = raw.replace("\r\n", "\n");
        String[] fmBody = splitFrontmatter(raw);
        Map<String, String> fm = parseFrontmatter(fmBody[0]);
        String slug = file.getFileName().toString().replaceAll("\\.md$", "");
        String rel = corpusRel(file);
        PageMeta meta = new PageMeta(
                group,
                slug,
                fm.getOrDefault("title", slug),
                fm.getOrDefault("url", ""),
                fm.getOrDefault("lastmod", ""),
                fm.getOrDefault("source", "xiaolincoding"),
                rel);
        return new ParsedPage(meta, sectionsOf(fmBody[1]));
    }

    private static String corpusRel(Path file) {
        return file.toString().replace('\\', '/');
    }

    // ------------------------------------------------------------------ 切题
    private static List<Section> sectionsOf(String body) {
        String[] lines = body.split("\n", -1);
        List<OutlineEntry> heads = new ArrayList<>();
        boolean inFence = false;
        for (int idx = 0; idx < lines.length; idx++) {
            String ln = lines[idx];
            String trimmed = ln.strip();
            if (trimmed.startsWith("```")) {
                inFence = !inFence; // 进/出代码围栏,围栏内不认标题
                continue;
            }
            if (inFence) {
                continue;
            }
            var m = HEADING.matcher(ln);
            if (m.matches()) {
                heads.add(new OutlineEntry(m.group(1).length(), cleanHeading(m.group(2)), idx));
            }
        }
        if (heads.isEmpty()) {
            return List.of();
        }

        List<Section> out = new ArrayList<>();
        Map<Integer, String> stack = new LinkedHashMap<>(); // level -> 最近标题文本
        for (int i = 0; i < heads.size(); i++) {
            OutlineEntry h = heads.get(i);
            // 清理比它深的层级(同层覆盖、深层出栈)
            stack.keySet().removeIf(lv -> lv > h.level());
            stack.put(h.level(), h.text());
            // 叶子:下一条标题 level <= 本 level(或已到末尾)=> 下面没有更低子标题
            boolean leaf = i == heads.size() - 1 || heads.get(i + 1).level() <= h.level();
            if (!leaf || h.level() == 1) {
                continue; // 根标题 / 容器类目不入库
            }
            // 正文到下一条标题行之前为止(不含那一行,否则下一题的标题会挂在本题答案末尾)
            int end = i + 1 < heads.size() ? heads.get(i + 1).line() : lines.length;
            String content = stripFooter(join(lines, h.line() + 1, end));
            String q = h.text();
            String key = normalizeKey(q);
            if (content.isBlank()) {
                continue; // 标题下无正文,丢弃
            }
            String category = h.level() >= 3 ? stack.get(h.level() - 1) : null;
            out.add(new Section(category, q, key, content));
        }
        return out;
    }

    private static String join(String[] lines, int fromLine, int toLine) {
        StringBuilder sb = new StringBuilder();
        for (int i = fromLine; i < toLine; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString().strip();
    }

    /** 去掉文末"关注公众号引流"段(它被算进最后一题的 content 里了)。 */
    private static String stripFooter(String content) {
        String[] paras = content.split("\n\\s*\n");
        List<String> keep = new ArrayList<>(List.of(paras));
        while (!keep.isEmpty() && FOOTER.matcher(keep.get(keep.size() - 1)).find()) {
            keep.remove(keep.size() - 1);
        }
        return String.join("\n\n", keep);
    }

    // ------------------------------------------------------------------ 文本清洗
    private static String cleanHeading(String text) {
        String t = text.strip();
        // 去 markdown 加粗/行内代码包裹
        t = t.replaceAll("^\\*\\*+|\\*\\*+$", "").replaceAll("^`+|`+$", "").strip();
        return t;
    }

    /** 题干归一化(检索键):小写、去标点空白。 */
    public static String normalizeKey(String s) {
        String t = s.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ frontmatter
    private static String[] splitFrontmatter(String raw) {
        if (!raw.startsWith("---")) {
            return new String[]{"", raw};
        }
        int second = raw.indexOf("\n---", 3);
        if (second < 0) {
            return new String[]{"", raw};
        }
        String fm = raw.substring(3, second);
        String body = raw.substring(second + 4);
        return new String[]{fm, body};
    }

    private static Map<String, String> parseFrontmatter(String fm) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String ln : fm.split("\n")) {
            int colon = ln.indexOf(':');
            if (colon > 0) {
                map.put(ln.substring(0, colon).strip(),
                        ln.substring(colon + 1).strip().replaceAll("^[\"']|[\"']$", ""));
            }
        }
        return map;
    }
}
