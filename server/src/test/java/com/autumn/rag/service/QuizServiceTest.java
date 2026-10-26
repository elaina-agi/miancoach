package com.autumn.rag.service;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.Question;
import com.autumn.rag.dao.QuizDao;
import com.autumn.rag.llm.ChatProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 不连库:DAO 用 Mockito 替身。覆盖"同一场不重复出题"的抽题规则。 */
class QuizServiceTest {

    private final UUID session = UUID.randomUUID();
    private CorpusDao corpus;
    private QuizDao quiz;
    private QuizService service;

    @BeforeEach
    void setUp() {
        corpus = mock(CorpusDao.class);
        quiz = mock(QuizDao.class);
        service = new QuizService(corpus, quiz, mock(ChatProvider.class), new ObjectMapper());
    }

    private static Question q(long id) {
        return new Question(id, "java", null, "题 " + id);
    }

    @Test
    void 已作答和前端跳过的题合并后一起排除() {
        when(quiz.answeredChunkIds(session)).thenReturn(List.of(1L, 2L));
        when(corpus.randomQuestion("java", List.of(1L, 2L, 3L))).thenReturn(q(9));

        // 前端传来的 exclude 和已作答的有重叠(2),也有空值,都不应该影响结果
        QuizService.StartOut out = service.pick(session, "java", Arrays.asList(2L, 3L, null));

        assertEquals(9L, out.chunkId());
        verify(corpus).randomQuestion("java", List.of(1L, 2L, 3L));
    }

    @Test
    void 范围内的题都出过一遍后放开限制允许重复() {
        when(quiz.answeredChunkIds(session)).thenReturn(List.of(1L));
        when(corpus.randomQuestion("java", List.of(1L))).thenReturn(null);
        when(corpus.randomQuestion("java")).thenReturn(q(1));

        assertEquals(1L, service.pick(session, "java", List.of()).chunkId());
    }

    @Test
    void 库里真没题时才报错() {
        when(quiz.answeredChunkIds(session)).thenReturn(List.of());
        when(corpus.randomQuestion("java", List.of())).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.pick(session, "java", null));
    }
}
