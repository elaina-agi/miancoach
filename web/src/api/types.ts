// 与后端接口对应的类型
export interface GroupCounts {
  [group: string]: number
}

export interface QNode {
  id: number
  question: string
}
export interface CatNode {
  category: string | null
  questions: QNode[]
}
export interface DocNode {
  slug: string
  title: string
  url: string
  categories: CatNode[]
}
export interface GroupNode {
  group: string
  docs: DocNode[]
}

export interface ChunkFull {
  chunkId: number
  docId: number
  group: string
  title: string
  url: string
  category: string | null
  question: string
  content: string
}

export interface AskRef {
  chunkId: number
  question: string
  category: string | null
  group: string
  title: string
  url: string
  excerpt: string
}

export interface QuizQuestion {
  sessionId: string
  chunkId: number
  group: string
  category: string | null
  question: string
}

export interface Grade {
  score: number | null
  hits: string[]
  missing: string[]
  comment: string
}

export interface GradeOut {
  answerId: number
  group: string
  category: string | null
  question: string
  title: string
  url: string
  grade: Grade
}

export interface SessionRow {
  id: string
  /** 全题库时后端省略这个 key(non_null) */
  groupTag?: string | null
  status: string
  createdAt: string
  answered: number
  /** 一题都没评分(mock 批改)时省略 */
  avgScore?: number | null
}

export interface QuizOverview {
  sessions: number
  answered: number
  avgScore?: number | null
  missed: number
}

export interface AnswerRow {
  id: number
  chunkId: number | null
  groupTag: string | null
  category: string | null
  question: string
  userAnswer: string
  score: number | null
  gradeJson: string | null
  createdAt: string
}

export interface WeakRow {
  groupTag?: string | null
  category?: string | null
  answered: number
  avgScore?: number | null
  missed: number
}

export interface ReindexResult {
  docs: number
  chunks: number
  groupChunks: GroupCounts
  realModel: boolean
}

export interface IndexStatus {
  indexed: boolean
  compatible: boolean
  indexedModel: string | null
  indexedDims: number | null
  indexedProvider: string | null
  indexedAt: string | null
  currentModel: string
  currentDims: number
  currentReal: boolean
  message: string
}

export interface MetaInfo {
  status: string
  chatReal: boolean
  chatModel: string
  embedReal: boolean
  embedModel: string
  embedDims: number
  totalChunks: number
  totalSegs: number
  docs: number
  index: IndexStatus
}

// ── 问答历史 ──────────────────────────────────────────────────────────
// updatedAt 是 ISO-8601 UTC(如 "2026-09-15T09:59:20.965Z"),
// new Date(...) 会自动转成本地时区,按本地日期分组即可。

export interface ChatSessionRow {
  id: string
  title: string
  createdAt: string
  updatedAt: string
}

export interface ChatMessageRow {
  id: number
  role: 'user' | 'assistant'
  content: string
  noAnswer: boolean
  createdAt: string
  /** 后端配了 non_null,用户消息没有这个 key(不是 null),所以是可选的 */
  refs?: AskRef[]
}

// ── 账号 ──────────────────────────────────────────────────────────────

export interface UserView {
  id: string
  email: string
  /** 没填昵称时后端不返回这个 key(non_null) */
  nickname?: string
  role: 'USER' | 'ADMIN'
}

export interface LoginOut {
  token: string
  user: UserView
}
