package com.autumn.rag.service;

import com.autumn.rag.common.ConflictException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 重建索引放到后台线程跑,接口立刻返回,前端轮询进度。
 *
 * <p><b>为什么不能"点一下、等它返回":</b>全量重建要把两千多个子段重新向量化,要跑好几分钟;
 * 线上前面有 Cloudflare,一个请求超过 100 秒就断开(524)。请求断了不代表任务停了,
 * 但前端拿不到结果,再点一次就会两份同时跑。
 *
 * <p>同一时间只允许一个任务:第二次 start 直接 409。状态放内存——单机部署,
 * 只有跑的时候需要看进度,重启丢了无所谓(索引本身的状态在 index_meta 里,/api/meta 能看到)。
 */
@Service
public class ReindexJobService {

    private static final Logger log = LoggerFactory.getLogger(ReindexJobService.class);

    public enum State { IDLE, RUNNING, SUCCEEDED, FAILED }

    /**
     * @param phase 当前阶段:"题目" / "子段",RUNNING 以外为 null
     * @param result 成功时的入库统计
     * @param error  失败原因
     */
    public record Status(State state, String phase, int done, int total, Instant startedAt, Instant finishedAt,
                         IngestService.ReindexResult result, String error) {

        static final Status IDLE = new Status(State.IDLE, null, 0, 0, null, null, null, null);
    }

    private final IngestService ingest;
    private final Executor executor;
    private volatile Status status = Status.IDLE;

    @Autowired
    public ReindexJobService(IngestService ingest) {
        this(ingest, Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "reindex");
            t.setDaemon(true);
            return t;
        }));
    }

    /** 包内可见:单测传一个同步执行器,start() 返回时任务已经跑完。 */
    ReindexJobService(IngestService ingest, Executor executor) {
        this.ingest = ingest;
        this.executor = executor;
    }

    /** synchronized:两个请求同时点"重建",只有一个能把状态改成 RUNNING。 */
    public synchronized Status start() {
        if (status.state() == State.RUNNING) {
            throw new ConflictException("索引正在重建中,等这一轮跑完再试");
        }
        Instant startedAt = Instant.now();
        status = new Status(State.RUNNING, "准备", 0, 0, startedAt, null, null, null);
        Status started = status;
        executor.execute(() -> run(startedAt));
        return started;
    }

    public Status status() {
        return status;
    }

    private void run(Instant startedAt) {
        log.info("开始重建索引");
        try {
            IngestService.ReindexResult result = ingest.reindex((phase, done, total) ->
                    status = new Status(State.RUNNING, phase, done, total, startedAt, null, null, null));
            status = new Status(State.SUCCEEDED, null, 0, 0, startedAt, Instant.now(), result, null);
            log.info("重建索引完成:{} 篇 / {} 题 / {} 子段", result.docs(), result.chunks(), result.segs());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.toString() : e.getMessage();
            status = new Status(State.FAILED, null, 0, 0, startedAt, Instant.now(), null, msg);
            log.error("重建索引失败,事务已回滚,库里仍是旧索引", e);
        }
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService es) {
            es.shutdownNow();
        }
    }
}
