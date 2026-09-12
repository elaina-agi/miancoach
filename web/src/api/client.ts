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
  report: (id: string) =>
    json<{ sessionId: string; answered: number; avgScore: number; report: string }>(`/api/quiz/session/${id}/report`),
  answers: (id: string) => json<AnswerRow[]>(`/api/quiz/session/${id}`),
  weakness: () => json<WeakRow[]>('/api/quiz/weakness'),
}

export interface StreamHandlers {
  onRefs?: (refs: AskRef[]) => void
  onDelta?: (text: string) => void
  onDone?: (info?: { noAnswer?: boolean }) => void
  onError?: (msg: string) => void
}

/** 多轮追问的一轮历史(后端据此把"它/那个"改写成独立问题再检索)。 */
export interface HistoryTurn { q: string; a: string }

/** POST /api/chat/ask 并以 SSE 事件驱动回调。返回 abort。 */
export function askStream(question: string, h: StreamHandlers, history?: HistoryTurn[]): () => void {
  const ctrl = new AbortController()
  void (async () => {
    let doneFired = false
    const fireDone = (info?: { noAnswer?: boolean }) => {
      if (!doneFired) {
        doneFired = true
        h.onDone?.(info)
      }
    }
    try {
      const resp = await fetch('/api/chat/ask', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ question, history: history ?? [] }),
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
        else if (ev === 'done') {
          let info: { noAnswer?: boolean } | undefined
          try { info = JSON.parse(payload) as { noAnswer?: boolean } } catch { /* 兼容空载荷 */ }
          fireDone(info)
        }
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
            else if (line.startsWith('data:')) {
              // SSE 规范:同一帧的多行 data 用 \n 连接(后端把含换行的 delta 拆成多行 data 发送);
              // 冒号后只剥一个空格,保住 markdown 里"嵌套列表/缩进"的前导空格
              const payload = line.slice(5)
              data += (data ? '\n' : '') + (payload.startsWith(' ') ? payload.slice(1) : payload)
            }
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
      fireDone()
    } catch (e) {
      if ((e as Error).name !== 'AbortError') h.onError?.((e as Error).message)
    }
  })()
  return () => ctrl.abort()
}
