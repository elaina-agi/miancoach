package com.autumn.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * OpenAI 兼容协议的最小 HTTP 封装(POST /chat/completions、/embeddings)。
 * chat 与 embedding 各持一份实例,base-url / key / 超时互不影响。
 */
public class OpenAiHttp {

    private static final String DONE = "[DONE]";

    private final ObjectMapper om;
    private final HttpClient http;

    public OpenAiHttp(ObjectMapper om, int connectTimeoutSecs) {
        this(om, connectTimeoutSecs, null, 0);
    }

    /**
     * proxyHost 配置时,该实例的所有请求经代理出去 —— 服务于 chat 走本机代理访问
     * Google 等直连不通的端点;embed/rerank 实例仍走两参构造保持直连。留空/端口 0 = 直连。
     */
    public OpenAiHttp(ObjectMapper om, int connectTimeoutSecs, String proxyHost, int proxyPort) {
        this.om = om;
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, connectTimeoutSecs)));
        if (proxyHost != null && !proxyHost.isBlank() && proxyPort > 0) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxyHost, proxyPort)));
        }
        this.http = builder.build();
    }

    /** 一次调用的目标(base-url + key + 超时)。 */
    public record Endpoint(String baseUrl, String apiKey, int timeoutSecs) {

        HttpRequest request(String path, Map<String, Object> body, ObjectMapper om) {
            byte[] payload;
            try {
                payload = om.writeValueAsBytes(body);
            } catch (Exception e) {
                throw new LlmException("序列化请求体失败", e);
            }
            return HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl.replaceAll("/+$", "") + path))
                    .timeout(Duration.ofSeconds(Math.max(5, timeoutSecs)))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
        }
    }

    /** 一次性 POST,2xx 返回 JSON;对 429/5xx 与网络错误退避重试。 */
    public JsonNode postJson(Endpoint ep, String path, Map<String, Object> body, int attempts) {
        for (int i = 0; i < attempts; i++) {
            try {
                HttpResponse<String> resp = http.send(ep.request(path, body, om),
                        HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 == 2) {
                    return om.readTree(resp.body());
                }
                String err = snippet(resp.body());
                if (i < attempts - 1 && (resp.statusCode() == 429 || resp.statusCode() >= 500)) {
                    sleep(2L * (i + 1));
                    continue;
                }
                throw new LlmException("HTTP " + resp.statusCode() + ": " + err);
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmException("调用被中断", e);
            } catch (Exception e) {
                if (i < attempts - 1) {
                    sleep(2L * (i + 1));
                    continue;
                }
                throw new LlmException("请求失败: " + e.getMessage(), e);
            }
        }
        throw new LlmException("请求失败(已重试 " + attempts + " 次)");
    }

    /**
     * 流式 POST:解析 SSE,把 delta.content 增量回调出去(在调用方线程上回调)。
     * <p>
     * firstTokenTimeoutSecs 秒内一个字都没出来就取消请求、抛 LlmException(上层据此切备用模型);
     * 出字之后中途停住超过同样的秒数也中断。0 = 不限。
     * 不能只靠 HttpRequest.timeout:它只管到响应头回来为止,之后模型卡住不出字会一直挂着。
     * 所以读流交给 HttpClient 自己的线程往队列里塞,这里带超时地取。
     * <p>
     * 模型的问题(连不上、非 2xx、超时、一个字都没返回)都抛 LlmException;
     * onDelta 自己抛的异常(如前端断开)原样往上抛——那不是模型的问题,上层不该因此切备用。
     */
    public void streamSse(Endpoint ep, String path, Map<String, Object> body, int firstTokenTimeoutSecs,
                          Consumer<String> onDelta) {
        SseLines lines = new SseLines();
        CompletableFuture<HttpResponse<String>> future;
        try {
            future = http.sendAsync(ep.request(path, body, om), lines::subscriber);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("流式请求失败: " + e.getMessage(), e); // 如 base-url 写错
        }
        future.whenComplete(lines::finished);
        long timeout = firstTokenTimeoutSecs > 0 ? TimeUnit.SECONDS.toNanos(firstTokenTimeoutSecs) : 0;
        long deadline = System.nanoTime() + timeout;
        boolean started = false;
        boolean finished = false;
        try {
            while (true) {
                Object item = timeout > 0
                        ? lines.queue.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)
                        : lines.queue.take();
                if (item == null) {
                    throw new LlmException(started
                            ? "模型输出中途停住超过 " + firstTokenTimeoutSecs + " 秒"
                            : "模型 " + firstTokenTimeoutSecs + " 秒内没有开始输出");
                }
                if (item == SseLines.END) {
                    break;
                }
                if (item instanceof LlmException e) {
                    throw e;
                }
                if (item instanceof Throwable t) {
                    throw new LlmException("流式请求失败: " + t.getMessage(), t);
                }
                String payload = ((String) item).startsWith("data:") ? ((String) item).substring(5).trim() : "";
                if (payload.isEmpty()) {
                    continue;
                }
                if (DONE.equals(payload)) {
                    break;
                }
                String text = deltaContent(payload);
                if (text == null) {
                    continue;
                }
                started = true;
                deadline = System.nanoTime() + timeout;
                onDelta.accept(text);
            }
            finished = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("调用被中断", e);
        } finally {
            if (!finished) {
                // 超时、出错、调用方中途不要了:都把请求掐掉,别让连接一直挂在那
                future.cancel(true);
                lines.cancel();
            }
        }
        if (!started) {
            // 有的中转站上游出错时也回 200,只是一个字没有:当成失败,有备用时才能切过去
            throw new LlmException("模型没有返回任何内容");
        }
    }

    /** 一行 SSE data 的增量文本;没有文本(只有 role、推理过程等)返回 null。 */
    private String deltaContent(String payload) {
        JsonNode root;
        try {
            root = om.readTree(payload);
        } catch (Exception e) {
            throw new LlmException("流式响应不是合法 JSON: " + snippet(payload), e);
        }
        // 有的服务在 200 的流里塞一条 {"error": ...}
        if (root.has("error")) {
            JsonNode err = root.path("error");
            throw new LlmException("模型报错: " + snippet(err.path("message").asText(err.toString())));
        }
        JsonNode delta = root.path("choices").path(0).path("delta").path("content");
        return delta.isTextual() && !delta.asText().isEmpty() ? delta.asText() : null;
    }

    /**
     * 在 HttpClient 的线程上把响应体逐行塞进队列,由 streamSse 带超时地取。
     * 队列里是:一行文本(String)、出错(Throwable)、或读完了(END)。
     */
    private static final class SseLines implements Flow.Subscriber<String> {

        static final Object END = new Object();

        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        private volatile Flow.Subscription subscription;

        /** 响应头到了:2xx 按行往队列里送;否则整段读成字符串,在 finished 里拼成报错 */
        HttpResponse.BodySubscriber<String> subscriber(HttpResponse.ResponseInfo info) {
            if (info.statusCode() / 100 != 2) {
                return HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            }
            return HttpResponse.BodySubscribers.fromLineSubscriber(this, s -> null, StandardCharsets.UTF_8, null);
        }

        void finished(HttpResponse<String> resp, Throwable err) {
            if (err != null) {
                queue.offer(err instanceof CompletionException && err.getCause() != null ? err.getCause() : err);
            } else if (resp.statusCode() / 100 != 2) {
                queue.offer(new LlmException("HTTP " + resp.statusCode() + ": " + snippet(resp.body())));
            }
        }

        void cancel() {
            Flow.Subscription s = subscription;
            if (s != null) {
                s.cancel();
            }
        }

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String line) {
            queue.offer(line);
        }

        @Override
        public void onError(Throwable t) {
            queue.offer(t);
        }

        @Override
        public void onComplete() {
            queue.offer(END);
        }
    }

    private static void sleep(long sec) {
        try {
            TimeUnit.SECONDS.sleep(sec);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String snippet(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
