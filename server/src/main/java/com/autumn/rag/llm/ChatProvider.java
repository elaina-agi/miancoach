package com.autumn.rag.llm;

import java.util.List;
import java.util.function.Consumer;

/** 生成端:对话(问答流式 / 批改一次性)。换厂商只影响这里,不碰向量。 */
public interface ChatProvider {

    /** 是否是真实模型(否=mock,用于无 key 联调)。 */
    boolean isReal();

    /** 标识,用于日志展示(如 openai:qwen-plus)。 */
    String name();

    /** 一次性对话(批改、总结用)。 */
    String chat(List<ChatMessage> messages);

    /** 流式对话(问答用),把增量文本回调出去。 */
    void chatStream(List<ChatMessage> messages, Consumer<String> onDelta);
}
