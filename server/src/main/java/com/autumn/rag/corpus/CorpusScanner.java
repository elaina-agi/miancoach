package com.autumn.rag.corpus;

import com.autumn.rag.corpus.MdParser.ParsedPage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** 扫描 corpus/ 下的语料:每个板块文件夹 = 一组,里面每份 .md = 一页。 */
public class CorpusScanner {

    private final Path corpusRoot;

    public CorpusScanner(Path corpusRoot) {
        this.corpusRoot = corpusRoot;
    }

    public Path root() {
        return corpusRoot;
    }

    /** 找出所有语料页并解析(group = 文件所在目录名,如 java/database/architecture/tools)。 */
    public List<ParsedPage> loadAll() {
        List<ParsedPage> pages = new ArrayList<>();
        if (!Files.isDirectory(corpusRoot)) {
            throw new IllegalStateException("corpus 目录不存在: " + corpusRoot);
        }
        try (Stream<Path> walk = Files.walk(corpusRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().toLowerCase().endsWith(".md"))
                    .toList();
            for (Path file : files) {
                Path parent = file.getParent();
                if (parent == null) {
                    continue;
                }
                String group = parent.getFileName().toString();
                // 顶层目录(如 interview)不算板块;真正的板块是放 md 的那一层
                pages.add(MdParser.parse(file, group));
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描 corpus 失败: " + corpusRoot, e);
        }
        return pages;
    }
}
