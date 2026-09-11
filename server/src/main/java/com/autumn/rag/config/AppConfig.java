package com.autumn.rag.config;

import com.autumn.rag.corpus.CorpusScanner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class AppConfig {

    /**
     * SSE 问答/批改的推送线程池:每个流式请求占一个线程直到 LLM 回完。
     * 本地单人使用,cached 池够用;线程设为 daemon 避免阻塞 JVM 退出。
     * (Spring 会在关闭时自动调用 ExecutorService.shutdown)
     */
    @Bean(name = "streamExecutor")
    public ExecutorService streamExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sse-stream-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    public CorpusScanner corpusScanner(AppProperties props) {
        return new CorpusScanner(Path.of(props.corpus().dir()));
    }
}
