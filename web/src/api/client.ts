import type {
  AnswerRow, AskRef, ChatMessageRow, ChatSessionRow, ChunkFull, GradeOut, GroupCounts, GroupNode,
  LoginOut, MetaInfo, QuizOverview, QuizQuestion, QuizReport, ReindexResult, ReportItem, SessionRow, UserView, WeakRow,
} from './types'
import { clearSession, token } from '../lib/session'

/** 带 HTTP 状态码的错误:调用方要区分 429(显示倒计时)和其它失败时用得上。 */
export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
  }
}

/** 已登录就带上 token;游客什么都不带,后端按游客处理。 */
function authHeaders(): Record<string, string> {
  return token.value ? { Authorization: `Bearer ${token.value}` } : {}
}

async function json<T>(path: string, init?: RequestInit): Promise<T> {
  const resp = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
  })
  if (!resp.ok) {
    let msg = `${resp.status} ${resp.statusText}`
    try {
      const body = await resp.json()
      if (body && body.error) msg = body.error
    } catch { /* ignore */ }
    // 带了 token 还被 401:token 过期或用户已删,本地登录态作废。
    // 没带 token 的 401(比如登录时密码错)不是登录态的问题,不能清
    if (resp.status === 401 && token.value) clearSession()
    throw new ApiError(msg, resp.status)
  }
  return resp.json() as Promise<T>
}

function post<T>(path: string, body: unknown): Promise<T> {
  return json<T>(path, { method: 'POST', body: JSON.stringify(body ?? {}) })
}

function del<T>(path: string): Promise<T> {
  return json<T>(path, { method: 'DELETE' })
}

export const chatApi = {
  /** 建会话,标题取首条问题前 30 字。返回的 id 随后要随 askStream 传回,那一轮问答才会落历史。 */
  createSession: (firstQuestion: string) =>
    post<{ sessionId: string }>('/api/chat/session', { firstQuestion }),
  /** 会话列表,按最后活跃倒序。「今天/昨天/近 7 天/更早」的分组在前端算(见 lib/format 的 dayBucket)。 */
  sessions: (limit = 50) => json<ChatSessionRow[]>(`/api/chat/sessions?limit=${limit}`),
  /** 回放:该会话的全部消息,按写入顺序。 */
  messages: (id: string) => json<ChatMessageRow[]>(`/api/chat/session/${id}`),
  /** 删除会话,消息由后端外键级联清除。 */
  remove: (id: string) => del<{ deleted: boolean }>(`/api/chat/session/${id}`),
}

export const authApi = {
  /** 发注册验证码。邮箱是否已注册返回都一样;60 秒内重复发 → 429 */
  sendCode: (email: string) => post<{ sent: boolean }>('/api/auth/code', { email }),
  register: (email: string, code: string, password: string, nickname?: string) =>
    post<LoginOut>('/api/auth/register', { email, code, password, nickname: nickname || null }),
  login: (email: string, password: string) => post<LoginOut>('/api/auth/login', { email, password }),
  me: () => json<UserView>('/api/auth/me'),
}

export const corpusApi = {
  tree: () => json<GroupNode[]>('/api/corpus/tree'),
  groups: () => json<GroupCounts>('/api/corpus/groups'),
  chunk: (id: number) => json<ChunkFull>(`/api/corpus/chunk/${id}`),
  meta: () => json<MetaInfo>('/api/meta'),
  reindex: () => post<ReindexResult>('/api/admin/reindex', {}),
}

// 登录用户走 session/{id} 这组(落库,复盘能回看);游客走 *Free 这组无状态接口,什么都不存
export const quizApi = {
  /** 登录时建场次;游客不建,返回里没有 sessionId */
  start: (groupTag?: string | null) => post<QuizQuestion>('/api/quiz/start', { groupTag: groupTag ?? null }),
  /** exclude:本场出过但没作答(被跳过)的题,后端再抽时跳过;已作答的后端自己会排除 */
  pick: (sessionId: string, groupTag?: string | null, exclude: number[] = []) =>
    post<QuizQuestion>(`/api/quiz/session/${sessionId}/pick`, { groupTag: groupTag ?? null, exclude }),
  grade: (sessionId: string, chunkId: number, userAnswer: string) =>
    post<GradeOut>(`/api/quiz/session/${sessionId}/grade`, { chunkId, userAnswer }),
  /** 只要答过题的场次:开了就走的空场次在复盘里没有意义 */
  sessions: () => json<SessionRow[]>('/api/quiz/sessions?limit=100&answeredOnly=true'),
  overview: () => json<QuizOverview>('/api/quiz/overview'),
  report: (id: string) => json<QuizReport>(`/api/quiz/session/${id}/report`),
  answers: (id: string) => json<AnswerRow[]>(`/api/quiz/session/${id}`),
  weakness: () => json<WeakRow[]>('/api/quiz/weakness'),

  /** 游客:本场出过的题全部放进 exclude(后端没有场次可查) */
  pickFree: (groupTag?: string | null, exclude: number[] = []) =>
    post<QuizQuestion>('/api/quiz/pick', { groupTag: groupTag ?? null, exclude }),
  gradeFree: (chunkId: number, userAnswer: string) => post<GradeOut>('/api/quiz/grade', { chunkId, userAnswer }),
  /** 游客:场次没存,把本场每题的批改结果传回去生成总结 */
  reportFree: (answers: ReportItem[]) => post<QuizReport>('/api/quiz/report', { answers }),
}

export interface StreamHandlers {
  onRefs?: (refs: AskRef[]) => void
  onDelta?: (text: string) => void
  onDone?: (info?: { noAnswer?: boolean }) => void
  onError?: (msg: string) => void
}

/** 多轮追问的一轮历史(后端据此把"它/那个"改写成独立问题再检索)。 */
export interface HistoryTurn { q: string; a: string }

/**
 * POST /api/chat/ask 并以 SSE 事件驱动回调。返回 abort。
 *
 * @param sessionId 历史会话 id。不传也能正常问答,只是这一问一答不落历史。
 */
export function askStream(question: string, h: StreamHandlers, history?: HistoryTurn[],
                          sessionId?: string): () => void {
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
        headers: { 'Content-Type': 'application/json', ...authHeaders() },
        body: JSON.stringify({ question, sessionId: sessionId ?? null, history: history ?? [] }),
        signal: ctrl.signal,
      })
      if (!resp.ok) {
        let msg = `HTTP ${resp.status}`
        try { const b = await resp.json(); if (b.error) msg = b.error } catch { /* ignore */ }
        // 和 json() 同一条规则:带着 token 被 401 = 登录态失效,清掉后界面回到游客
        if (resp.status === 401 && token.value) clearSession()
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
