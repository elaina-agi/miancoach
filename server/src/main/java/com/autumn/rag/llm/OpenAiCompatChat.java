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

    private static final int ATTEMPTS = 3;

    private final AppProperties.Chat props;
    private final OpenAiHttp http;

    public OpenAiCompatChat(AppProperties.Chat props, ObjectMapper om) {
        this.props = props;
        this.http = new OpenAiHttp(om, props.connectTimeoutSecs(), props.proxyHost(), props.proxyPort());
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
        JsonNode root = http.postJson(endpoint(), "/chat/completions", body(messages, false), ATTEMPTS);
        return root.path("choices").path(0).path("message").path("content").asText();
    }

    @Override
    public void chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
        http.streamSse(endpoint(), "/chat/completions", body(messages, true), onDelta);
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
