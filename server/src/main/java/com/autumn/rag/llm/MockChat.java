package com.autumn.rag.llm;

import java.util.List;
import java.util.function.Consumer;

/** Mock 生成端:返回占位回答并提示去配 key,让无 key 时 UI/链路也能跑。 */
public class MockChat implements ChatProvider {

    @Override
    public boolean isReal() {
        return false;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        return "（Mock 回答）针对「" + cut(lastUserText(messages), 40) + "」的占位输出。\n\n"
                + "当前是 mock 模式,检索链路已验证。要接真实大模型:在项目根 .env 里设置\n"
                + "  BAGUWEN_CHAT_PROVIDER=openai\n"
                + "  BAGUWEN_CHAT_API_KEY=你的百炼key\n"
                + "然后重启 server。";
    }

    @Override
    public void chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
        onDelta.accept(chat(messages));
    }

    private static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if ("user".equals(messages.get(i).role())) {
                return messages.get(i).content();
            }
        }
        return "";
    }

    private static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
