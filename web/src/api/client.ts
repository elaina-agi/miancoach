import type {
  AnswerRow, AskRef, ChunkFull, GradeOut, GroupCounts, GroupNode,
  MetaInfo, QuizQuestion, ReindexResult, SessionRow, WeakRow,
} from './types'

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!resp.ok) {
    let msg = `${resp.status} ${resp.statusText}`
    try {
      const body = await resp.json()
      if (body && body.error) msg = body.error
    } catch { /* ignore */ }
    throw new Error(msg)
  }
  return resp.json() as Promise<T>
}

function post<T>(path: string, body: unknown): Promise<T> {
  return json<T>(path, { method: 'POST', body: JSON.stringify(body ?? {}) })
}

export const corpusApi = {
  tree: () => json<GroupNode[]>('/api/corpus/tree'),
  groups: () => json<GroupCounts>('/api/corpus/groups'),
  chunk: (id: number) => json<ChunkFull>(`/api/corpus/chunk/${id}`),
  meta: () => json<MetaInfo>('/api/meta'),
  reindex: () => post<ReindexResult>('/api/admin/reindex', {}),
}

export const quizApi = {
  start: (groupTag?: string) => post<QuizQuestion>('/api/quiz/start', { groupTag: groupTag ?? null }),
  pick: (sessionId: string, groupTag?: string) =>
    post<QuizQuestion>(`/api/quiz/session/${sessionId}/pick`, { groupTag: groupTag ?? null }),
  grade: (sessionId: string, chunkId: number, userAnswer: string) =>
    post<GradeOut>(`/api/quiz/session/${sessionId}/grade`, { chunkId, userAnswer }),
  sessions: () => json<SessionRow[]>('/api/quiz/sessions?limit=100'),
  answers: (id: string) => json<AnswerRow[]>(`/api/quiz/session/${id}`),
  weakness: () => json<WeakRow[]>('/api/quiz/weakness'),
}

export interface StreamHandlers {
  onRefs?: (refs: AskRef[]) => void
  onDelta?: (text: string) => void
  onDone?: () => void
  onError?: (msg: string) => void
}

/** POST /api/chat/ask 并以 SSE 事件驱动回调。返回 abort。 */
export function askStream(question: string, h: StreamHandlers): () => void {
  const ctrl = new AbortController()
  void (async () => {
    try {
      const resp = await fetch('/api/chat/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question }),
        signal: ctrl.signal,
      })
      if (!resp.ok) {
        let msg = `HTTP ${resp.status}`
        try { const b = await resp.json(); if (b.error) msg = b.error } catch { /* ignore */ }
        h.onError?.(msg)
        return
      }
      const reader = resp.body?.getReader()
      if (!reader) { h.onError?.('无响应流'); return }
      const decoder = new TextDecoder('utf-8')
      let buffer = ''
      let event = ''
      let data = ''
      const flush = () => {
        if (event && data !== undefined) dispatch(event, data)
        event = ''
        data = ''
      }
      const dispatch = (ev: string, payload: string) => {
        if (ev === 'refs') h.onRefs?.(JSON.parse(payload) as AskRef[])
        else if (ev === 'delta') h.onDelta?.(payload)
        else if (ev === 'done') h.onDone?.()
        else if (ev === 'error') h.onError?.(payload)
      }
      for (;;) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let idx: number
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
          const frame = buffer.slice(0, idx)
          buffer = buffer.slice(idx + 2)
          for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) event = line.slice(6).trim()
            else if (line.startsWith('data:')) data += line.slice(5).trim()
          }
          if (event === 'delta' && data) {
            // 逐块推送,避免一次性 append 大文本
            h.onDelta?.(data)
            data = ''
            event = ''
          } else if (event) {
            flush()
          }
        }
      }
      if (event) flush()
      h.onDone?.()
    } catch (e) {
      if ((e as Error).name !== 'AbortError') h.onError?.((e as Error).message)
    }
  })()
  return () => ctrl.abort()
}
