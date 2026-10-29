package com.autumn.rag.llm;

import com.autumn.rag.config.AppProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 生成端:OpenAI 兼容 /chat/completions(默认阿里云百炼 compatible-mode)。 */
public class OpenAiCompatChat implements ChatProvider {

    private final AppProperties.Chat props;
    private final OpenAiHttp http;
    private final int attempts;

    /**
     * attempts:一次性调用遇到 429/5xx/网络错误时最多试几次。
     * 有备用模型时主模型只试 1 次——失败了直接切备用,比在一个挂掉的服务上反复等强。
     */
    public OpenAiCompatChat(AppProperties.Chat props, ObjectMapper om, int attempts) {
        this.props = props;
        this.http = new OpenAiHttp(om, props.connectTimeoutSecs(), props.proxyHost(), props.proxyPort());
        this.attempts = attempts;
    }

    @Override
    public boolean isReal() {
        return true;
    }

    @Override
    public String name() {
        return "openai:" + props.model();
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        JsonNode root = http.postJson(endpoint(), "/chat/completions", body(messages, false), attempts);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        // 有的中转站上游出错时也回 200,只是内容为空:当成失败,有备用时才能切过去
        if (content.isBlank()) {
            throw new LlmException("模型返回了空内容");
        }
        return content;
    }

    @Override
    public void chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
        http.streamSse(endpoint(), "/chat/completions", body(messages, true), props.firstTokenTimeoutSecs(), onDelta);
    }

    private OpenAiHttp.Endpoint endpoint() {
        return new OpenAiHttp.Endpoint(props.baseUrl(), props.apiKey(), props.requestTimeoutSecs());
    }

    private Map<String, Object> body(List<ChatMessage> messages, boolean stream) {
        List<Map<String, String>> msgs = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            Map<String, String> one = new LinkedHashMap<>();
            one.put("role", m.role());
            one.put("content", m.content());
            msgs.add(one);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.model());
        body.put("messages", msgs);
        body.put("stream", stream);
        body.put("temperature", 0.3);
        return body;
    }
}
