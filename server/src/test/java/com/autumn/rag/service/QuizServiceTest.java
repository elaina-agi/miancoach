package com.autumn.rag.service;

import com.autumn.rag.common.NotFoundException;
import com.autumn.rag.dao.CorpusDao;
import com.autumn.rag.dao.CorpusDao.ChunkFull;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 不连库:DAO 用 Mockito 替身。覆盖"同一场不重复出题"的抽题规则,以及场次归属 / 游客不落库。 */
class QuizServiceTest {

    private final UUID user = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private CorpusDao corpus;
    private QuizDao quiz;
    private QuizService service;

    @BeforeEach
    void setUp() {
        corpus = mock(CorpusDao.class);
        quiz = mock(QuizDao.class);
        service = new QuizService(corpus, quiz, mock(ChatProvider.class), new ObjectMapper());
        when(quiz.ownsSession(session, user)).thenReturn(true);
    }

    private static Question q(long id) {
        return new Question(id, "java", null, "题 " + id);
    }

    @Test
    void 已作答和前端跳过的题合并后一起排除() {
        when(quiz.answeredChunkIds(session)).thenReturn(List.of(1L, 2L));
        when(corpus.randomQuestion("java", List.of(1L, 2L, 3L))).thenReturn(q(9));

        // 前端传来的 exclude 和已作答的有重叠(2),也有空值,都不应该影响结果
        QuizService.StartOut out = service.pick(user, session, "java", Arrays.asList(2L, 3L, null));

        assertEquals(9L, out.chunkId());
        verify(corpus).randomQuestion("java", List.of(1L, 2L, 3L));
    }

    @Test
    void 范围内的题都出过一遍后放开限制允许重复() {
        when(quiz.answeredChunkIds(session)).thenReturn(List.of(1L));
        when(corpus.randomQuestion("java", List.of(1L))).thenReturn(null);
        when(corpus.randomQuestion("java")).thenReturn(q(1));

        assertEquals(1L, service.pick(user, session, "java", List.of()).chunkId());
    }

    @Test
    void 库里真没题时才报错() {
        when(quiz.answeredChunkIds(session)).thenReturn(List.of());
        when(corpus.randomQuestion("java", List.of())).thenReturn(null);

        assertThrows(NotFoundException.class, () -> service.pick(user, session, "java", null));
    }

    @Test
    void 别人的场次和不存在的一样是404() {
        UUID stranger = UUID.randomUUID();

        assertThrows(NotFoundException.class, () -> service.pick(stranger, session, "java", List.of()));
        assertThrows(NotFoundException.class, () -> service.grade(stranger, session, 1L, "答案"));
        assertThrows(NotFoundException.class, () -> service.sessionAnswers(stranger, session));
        assertThrows(NotFoundException.class, () -> service.report(stranger, session));
        verify(quiz, never()).answeredChunkIds(any());
        verify(quiz, never()).answers(any());
    }

    @Test
    void 游客开场不建场次() {
        when(corpus.randomQuestion("java", List.of())).thenReturn(q(5));

        QuizService.StartOut out = service.start(null, "java");

        assertNull(out.sessionId());
        assertEquals(5L, out.chunkId());
        verify(quiz, never()).createSession(any(), any());
    }

    @Test
    void 游客抽题只按前端传的exclude排除() {
        when(corpus.randomQuestion("java", List.of(3L, 4L))).thenReturn(q(7));

        assertEquals(7L, service.pickFree("java", Arrays.asList(3L, null, 4L)).chunkId());
    }

    @Test
    void 游客批改不落库() {
        when(corpus.getChunk(1L)).thenReturn(new ChunkFull(1L, 1L, "java", "标题", "url", null, "题 1", "原文"));

        QuizService.GradeOut out = service.gradeFree(1L, "我的答案");

        assertNull(out.answerId());
        verifyNoInteractions(quiz);
    }
}
