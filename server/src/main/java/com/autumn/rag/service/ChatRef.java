package com.autumn.rag.service;

/**
 * 引用卡片:回答里 [n] 标号对应的那篇材料,前端点击可追读原文。
 *
 * <p>从 AskService 的内部 record 提成顶层类型,因为它现在有两个使用者:
 * {@link AskService} 生成它(随 SSE 推给前端),{@link ChatService} 把它序列化进
 * chat_message.refs 落库、读历史会话时再反序列化回来。
 * 留在 AskService 里会让 ChatService 反向依赖 AskService,与"AskService 调 ChatService
 * 存消息"构成循环依赖(Spring Boot 2.6+ 默认禁止)。
 *
 * <p>落库时存的是**整份快照**而不是只存 chunkId——reindex 会重建所有 chunk id,
 * 只存 id 的话重灌后引用卡片就查不到内容了(详见 V5 迁移的注释)。
 *
 * @param chunkId 语料块 id。仅作为"如果还有效就深链接过去"的线索,不保证有效
 * @param excerpt 命中片段预览(约 160 字);子段命中时是子段正文,更精准
 */
public record ChatRef(long chunkId, String question, String category, String group,
                      String title, String url, String excerpt) {}
