package com.autumn.rag.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 流式读取:正常逐段回调,以及各种"模型出问题"都要在超时内变成 LlmException。起一个本地假服务来模拟。 */
class OpenAiHttpStreamTest {

    private interface Handler {
        void handle(HttpExchange ex) throws IOException;
    }

    private HttpServer server;
    private ExecutorService pool;
    private volatile Handler handler;
    /** 模拟"卡住"的处理器一直等它,测试结束时放行 */
    private final CountDownLatch release = new CountDownLatch(1);

    private final OpenAiHttp http = new OpenAiHttp(new ObjectMapper(), 5);
    private final List<String> got = new ArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", ex -> handler.handle(ex));
        pool = Executors.newCachedThreadPool();
        server.setExecutor(pool);
        server.start();
    }

    @AfterEach
    void stop() {
        release.countDown();
        server.stop(0);
        pool.shutdownNow();
    }

    private void stream(int firstTokenTimeoutSecs) {
        OpenAiHttp.Endpoint ep = new OpenAiHttp.Endpoint(
                "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 30);
        http.streamSse(ep, "/chat/completions", Map.of("stream", true), firstTokenTimeoutSecs, got::add);
    }

    private static String delta(String text) {
        return "{\"choices\":[{\"delta\":{\"content\":\"" + text + "\"}}]}";
    }

    private static final String ROLE_ONLY = "{\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}";

    /** 回 200 的 SSE,逐条写出并立刻 flush;hang = 写完不结束,一直挂着 */
    private void sse(HttpExchange ex, boolean hang, String... events) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.sendResponseHeaders(200, 0);
        OutputStream out = ex.getResponseBody();
        for (String e : events) {
            out.write(("data: " + e + "\n\n").getBytes(UTF_8));
            out.flush();
        }
        if (hang) {
            hang();
        }
        out.close();
    }

    private void hang() {
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 超时类的用例:必须在超时后很快结束,而不是一直挂着 */
    private LlmException timesOut(int timeoutSecs) {
        long t0 = System.nanoTime();
        LlmException e = assertThrows(LlmException.class, () -> stream(timeoutSecs));
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms >= timeoutSecs * 1000L - 100 && ms < timeoutSecs * 1000L + 2000, "实际耗时 " + ms + "ms");
        return e;
    }

    @Test
    void 正常输出时逐段回调_不限时也能读完() {
        handler = ex -> sse(ex, false, ROLE_ONLY, delta("你"), delta("好"), "[DONE]");

        stream(0);

        assertEquals(List.of("你", "好"), got);
    }

    @Test
    void 响应头都不回_首字超时() {
        handler = ex -> hang();

        LlmException e = timesOut(1);

        assertEquals("模型 1 秒内没有开始输出", e.getMessage());
    }

    @Test
    void 连上了但一个字不出_首字超时() {
        handler = ex -> sse(ex, true, ROLE_ONLY); // 只回了角色,没有内容

        LlmException e = timesOut(1);

        assertEquals("模型 1 秒内没有开始输出", e.getMessage());
        assertTrue(got.isEmpty());
    }

    @Test
    void 出字之后中途停住_也会中断() {
        handler = ex -> sse(ex, true, delta("前半句"));

        LlmException e = timesOut(1);

        assertEquals("模型输出中途停住超过 1 秒", e.getMessage());
        assertEquals(List.of("前半句"), got);
    }

    @Test
    void 非2xx_报出状态码和错误内容() {
        handler = ex -> {
            byte[] body = "{\"error\":{\"message\":\"upstream down\"}}".getBytes(UTF_8);
            ex.sendResponseHeaders(502, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        };

        LlmException e = assertThrows(LlmException.class, () -> stream(5));

        assertTrue(e.getMessage().startsWith("HTTP 502: "), e.getMessage());
        assertTrue(e.getMessage().contains("upstream down"), e.getMessage());
    }

    @Test
    void 回了200但一个字没有_也算失败() {
        handler = ex -> sse(ex, false, ROLE_ONLY, "[DONE]");

        LlmException e = assertThrows(LlmException.class, () -> stream(5));

        assertEquals("模型没有返回任何内容", e.getMessage());
    }

    @Test
    void 流里夹着error_报出来() {
        handler = ex -> sse(ex, false, "{\"error\":{\"message\":\"quota exceeded\"}}");

        LlmException e = assertThrows(LlmException.class, () -> stream(5));

        assertEquals("模型报错: quota exceeded", e.getMessage());
    }

    @Test
    void 连不上_报LlmException() {
        handler = ex -> { };
        int port = server.getAddress().getPort();
        server.stop(0); // 端口上没人听了
        OpenAiHttp.Endpoint ep = new OpenAiHttp.Endpoint("http://127.0.0.1:" + port, "test-key", 30);

        assertThrows(LlmException.class,
                () -> http.streamSse(ep, "/chat/completions", Map.of("stream", true), 5, got::add));
    }

    @Test
    void 回调抛的异常原样抛出_不包成模型失败() {
        handler = ex -> sse(ex, false, delta("你"), delta("好"), "[DONE]");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> {
            OpenAiHttp.Endpoint ep = new OpenAiHttp.Endpoint(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "test-key", 30);
            http.streamSse(ep, "/chat/completions", Map.of("stream", true), 5, d -> {
                throw new IllegalStateException("SSE 推送中断");
            });
        });

        assertEquals("SSE 推送中断", e.getMessage());
    }
}
