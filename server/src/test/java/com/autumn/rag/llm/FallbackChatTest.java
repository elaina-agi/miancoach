package com.autumn.rag.llm;

import com.autumn.rag.TestClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 主模型失败切备用、流式出字后不切、回调异常不算模型失败、连续失败熔断与恢复。 */
class FallbackChatTest {

    /** 按开关行事的假模型:流式正常时吐两段 */
    private static final class FakeChat implements ChatProvider {
        private final String name;
        boolean fail;                 // 一上来就失败
        boolean failAfterFirstDelta;  // 流式吐出第一段后失败
        int calls;

        FakeChat(String name) {
            this.name = name;
        }

        @Override
        public boolean isReal() {
            return true;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String chat(List<ChatMessage> messages) {
            calls++;
            if (fail) {
                throw new LlmException(name + " 挂了");
            }
            return name + " 的回答";
        }

        @Override
        public void chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
            calls++;
            if (fail) {
                throw new LlmException(name + " 挂了");
            }
            onDelta.accept(name + ":第一段");
            if (failAfterFirstDelta) {
                throw new LlmException(name + " 中途断了");
            }
            onDelta.accept(name + ":第二段");
        }
    }

    private static final List<ChatMessage> Q = List.of(ChatMessage.user("问题"));

    private final TestClock clock = new TestClock();
    private final FakeChat primary = new FakeChat("主");
    private final FakeChat backup = new FakeChat("备");
    private final FallbackChat chat = new FallbackChat(primary, backup, 3, Duration.ofSeconds(60), clock);

    private List<String> stream() {
        List<String> out = new ArrayList<>();
        chat.chatStream(Q, out::add);
        return out;
    }

    @Test
    void 主模型正常时不碰备用() {
        assertEquals("主 的回答", chat.chat(Q));
        assertEquals(List.of("主:第一段", "主:第二段"), stream());
        assertEquals(0, backup.calls);
    }

    @Test
    void 主模型失败就切到备用() {
        primary.fail = true;
        assertEquals("备 的回答", chat.chat(Q));
        assertEquals(List.of("备:第一段", "备:第二段"), stream());
    }

    @Test
    void 流式已经出字后断了不切备用() {
        primary.failAfterFirstDelta = true;
        List<String> out = new ArrayList<>();

        LlmException e = assertThrows(LlmException.class, () -> chat.chatStream(Q, out::add));

        assertEquals("主 中途断了", e.getMessage());
        assertEquals(List.of("主:第一段"), out); // 没混进备用模型从头写的内容
        assertEquals(0, backup.calls);
    }

    @Test
    void 回调自己抛的异常不切备用也不计失败() {
        for (int i = 0; i < 5; i++) {
            assertThrows(IllegalStateException.class, () -> chat.chatStream(Q, d -> {
                throw new IllegalStateException("前端断开了");
            }));
        }
        assertEquals(0, backup.calls);

        chat.chat(Q);
        assertEquals(6, primary.calls); // 没有熔断,还在走主模型
    }

    @Test
    void 备用也失败时两边都报() {
        primary.fail = true;
        backup.fail = true;

        LlmException e = assertThrows(LlmException.class, () -> chat.chat(Q));

        assertTrue(e.getMessage().startsWith("主模型和备用模型都失败了"), e.getMessage());
        assertTrue(e.getMessage().contains("备 挂了"), e.getMessage());
    }

    @Test
    void 连续失败熔断_期间直接走备用_到点再试主模型() {
        primary.fail = true;
        for (int i = 0; i < 3; i++) {
            chat.chat(Q);
        }
        assertEquals(3, primary.calls);

        // 熔断中:一次性调用和流式都不再先试主模型
        assertEquals("备 的回答", chat.chat(Q));
        assertEquals(List.of("备:第一段", "备:第二段"), stream());
        assertEquals(3, primary.calls);

        // 到点了,主模型也好了:试一次成功,之后一直走主模型
        clock.plus(Duration.ofSeconds(60));
        primary.fail = false;
        assertEquals("主 的回答", chat.chat(Q));
        assertEquals("主 的回答", chat.chat(Q));
        assertEquals(5, primary.calls);
    }

    @Test
    void 熔断到点后主模型还是失败_立刻再熔断() {
        primary.fail = true;
        for (int i = 0; i < 3; i++) {
            chat.chat(Q);
        }
        clock.plus(Duration.ofSeconds(60));

        chat.chat(Q); // 到点试一次,又失败
        chat.chat(Q);
        chat.chat(Q);

        assertEquals(4, primary.calls); // 只试了那一次,没有每次都重新等主模型失败
    }

    @Test
    void 中间成功一次失败次数就清零() {
        primary.fail = true;
        chat.chat(Q);
        chat.chat(Q);
        primary.fail = false;
        chat.chat(Q);
        primary.fail = true;
        chat.chat(Q);
        chat.chat(Q);
        chat.chat(Q); // 清零后的第 3 次,还会走主模型

        assertEquals(6, primary.calls);
    }

    @Test
    void 熔断次数配0表示不熔断() {
        FallbackChat noBreaker = new FallbackChat(primary, backup, 0, Duration.ofSeconds(60), clock);
        primary.fail = true;
        for (int i = 0; i < 10; i++) {
            noBreaker.chat(Q);
        }
        assertEquals(10, primary.calls);
    }
}
