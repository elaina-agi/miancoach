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

// ── 题库收藏(用户归属由登录态决定,前端不传 userId) ────────────────────

export interface FavoriteState {
  favorited: boolean
}

export interface FavoriteItem {
  chunkId: number
  group: string
  title: string
  category: string | null
  question: string
  createdAt: string
}

export interface FavoritePage {
  items: FavoriteItem[]
  total: number
  page: number
  size: number
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
  /** 游客不建场次,后端省略这个 key */
  sessionId?: string
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
  /** 游客批改不落库,后端省略这个 key */
  answerId?: number
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

export interface QuizReport {
  /** 游客的总结没有场次,后端省略这个 key */
  sessionId?: string
  answered: number
  avgScore: number
  report: string
}

/** 游客生成总结时传回去的一题 */
export interface ReportItem {
  question: string
  groupTag: string | null
  score: number | null
  hits: string[]
  missing: string[]
}

export interface WeakRow {
  groupTag?: string | null
  category?: string | null
  answered: number
  avgScore?: number | null
  missed: number
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

// ── 管理后台 ──────────────────────────────────────────────────────────

export interface ReindexResult {
  docs: number
  chunks: number
  segs: number
  groupChunks: GroupCounts
  embedModel: string
  embedDims: number
  embedReal: boolean
}

/** 重建索引是后台任务:POST 立刻返回,之后轮询 GET 看进度。null 字段后端不返回(non_null) */
export interface ReindexStatus {
  /** IDLE = 服务这次启动后还没跑过 */
  state: 'IDLE' | 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  /** 跑的时候在哪个阶段:准备 / 题目 / 子段 */
  phase?: string
  done: number
  total: number
  startedAt?: string
  finishedAt?: string
  result?: ReindexResult
  error?: string
}

export interface AdminUser {
  id: string
  email: string
  nickname?: string
  role: 'USER' | 'ADMIN'
  createdAt: string
  lastLoginAt?: string
  /** 有值 = 已禁用,值是禁用的时间 */
  disabledAt?: string
  chatSessions: number
  /** 只算答过题的场次,和复盘页一致 */
  quizSessions: number
}

export interface AdminUserPage {
  items: AdminUser[]
  total: number
  page: number
  size: number
}
