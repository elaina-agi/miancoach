package com.autumn.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
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

    /** 流式 POST:解析 SSE,把 delta.content 增量回调出去。 */
    public void streamSse(Endpoint ep, String path, Map<String, Object> body, Consumer<String> onDelta) {
        try {
            HttpResponse<InputStream> resp = http.send(ep.request(path, body, om),
                    HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                throw new LlmException("HTTP " + resp.statusCode() + ": " + drain(resp.body(), 500));
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty()) {
                        continue;
                    }
                    if (DONE.equals(payload)) {
                        break;
                    }
                    JsonNode delta = om.readTree(payload).path("choices").path(0).path("delta").path("content");
                    if (delta.isTextual() && !delta.asText().isEmpty()) {
                        onDelta.accept(delta.asText());
                    }
                }
            }
        } catch (LlmException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmException("流式请求失败: " + e.getMessage(), e);
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

    private static String drain(InputStream in, int max) {
        try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[512];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
                if (sb.length() >= max) {
                    break;
                }
            }
            return sb.toString();
        } catch (Exception ignore) {
            return "(读取错误响应失败)";
        }
    }
}
