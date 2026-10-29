package com.autumn.rag.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 生成端兜底:主模型失败就切到备用模型。问答、追问改写、批改、总结都走这里,业务代码不用管。
 * <ul>
 *   <li>一次性调用:主模型抛 LlmException(连不上、超时、限流、5xx、key 失效、空内容)就改用备用;</li>
 *   <li>流式问答:只在第一个字出来之前切。已经吐出半段再断,换个模型接着写会前后对不上,原样报错让用户重试;</li>
 *   <li>熔断:主模型连续失败 breakerFailures 次后,breakerOpen 这段时间直接走备用——
 *       不然主模型挂着的时候,每个请求都要先白等它失败(首字超时要几十秒)。到点再试主模型,成功就恢复。</li>
 * </ul>
 * 回调里抛的异常(如前端断开)不是模型的问题:不切备用,也不计失败。
 */
public class FallbackChat implements ChatProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackChat.class);

    private final ChatProvider primary;
    private final ChatProvider backup;
    private final int breakerFailures;
    private final Duration breakerOpen;
    private final Clock clock;

    // 熔断状态,读写都在 synchronized 方法里;调模型本身不持锁
    private int failures;
    private Instant openUntil = Instant.MIN;

    public FallbackChat(ChatProvider primary, ChatProvider backup, int breakerFailures, Duration breakerOpen) {
        this(primary, backup, breakerFailures, breakerOpen, Clock.systemUTC());
    }

    FallbackChat(ChatProvider primary, ChatProvider backup, int breakerFailures, Duration breakerOpen, Clock clock) {
        this.primary = primary;
        this.backup = backup;
        this.breakerFailures = breakerFailures;
        this.breakerOpen = breakerOpen;
        this.clock = clock;
    }

    @Override
    public boolean isReal() {
        return primary.isReal();
    }

    @Override
    public String name() {
        return primary.name() + " / 备用 " + backup.name();
    }

    @Override
    public String chat(List<ChatMessage> messages) {
        if (primaryAvailable()) {
            try {
                String out = primary.chat(messages);
                primarySucceeded();
                return out;
            } catch (LlmException e) {
                primaryFailed(e, true);
            }
        }
        return viaBackup(() -> backup.chat(messages));
    }

    @Override
    public void chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
        if (primaryAvailable()) {
            boolean[] started = {false};
            try {
                primary.chatStream(messages, delta -> {
                    started[0] = true;
                    onDelta.accept(delta);
                });
                primarySucceeded();
                return;
            } catch (LlmException e) {
                primaryFailed(e, !started[0]);
                if (started[0]) {
                    throw e;
                }
            }
        }
        viaBackup(() -> {
            backup.chatStream(messages, onDelta);
            return null;
        });
    }

    private <T> T viaBackup(Supplier<T> call) {
        try {
            return call.get();
        } catch (LlmException e) {
            throw new LlmException("主模型和备用模型都失败了。备用模型:" + e.getMessage(), e);
        }
    }

    /** 没在熔断期,就先试主模型 */
    private synchronized boolean primaryAvailable() {
        return !clock.instant().isBefore(openUntil);
    }

    private synchronized void primarySucceeded() {
        if (breakerFailures > 0 && failures >= breakerFailures) {
            log.info("主模型 {} 恢复了,切回主模型", primary.name());
        }
        failures = 0;
    }

    private synchronized void primaryFailed(LlmException e, boolean switching) {
        failures++;
        if (switching) {
            log.warn("主模型 {} 失败,改用备用 {}: {}", primary.name(), backup.name(), e.getMessage());
        } else {
            log.warn("主模型 {} 输出到一半失败,已经吐出部分内容,不切备用: {}", primary.name(), e.getMessage());
        }
        if (breakerFailures > 0 && failures >= breakerFailures) {
            openUntil = clock.instant().plus(breakerOpen);
            log.warn("主模型 {} 连续失败 {} 次,接下来 {} 秒直接走备用", primary.name(), failures, breakerOpen.toSeconds());
        }
    }
}
