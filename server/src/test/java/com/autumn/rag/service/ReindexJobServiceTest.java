package com.autumn.rag.service;

import com.autumn.rag.common.ConflictException;
import com.autumn.rag.service.ReindexJobService.State;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReindexJobServiceTest {

    private final IngestService ingest = mock(IngestService.class);

    @Test
    void 跑着的时候再点一次_409() {
        // 任务先攒着不执行,模拟"还在跑"
        List<Runnable> queued = new ArrayList<>();
        Executor held = queued::add;
        ReindexJobService job = new ReindexJobService(ingest, held);

        assertEquals(State.RUNNING, job.start().state());
        assertThrows(ConflictException.class, job::start);
        assertEquals(1, queued.size());
    }

    @Test
    void 成功后记结果_可以再跑() {
        IngestService.ReindexResult result =
                new IngestService.ReindexResult(1, 2, 3, Map.of(), "m", 1024, true);
        when(ingest.reindex(any())).thenReturn(result);
        ReindexJobService job = new ReindexJobService(ingest, Runnable::run);

        job.start();

        assertEquals(State.SUCCEEDED, job.status().state());
        assertEquals(result, job.status().result());
        assertNotNull(job.status().finishedAt());
        job.start(); // 跑完了可以再跑,不会 409
    }

    @Test
    void 失败后记原因_可以重试() {
        when(ingest.reindex(any())).thenThrow(new IllegalStateException("向量接口 503"));
        ReindexJobService job = new ReindexJobService(ingest, Runnable::run);

        job.start();

        assertEquals(State.FAILED, job.status().state());
        assertEquals("向量接口 503", job.status().error());
        job.start();
    }
}
