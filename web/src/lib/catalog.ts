// 题库与服务状态的共享数据:顶栏、问答页、知识库、模拟面试都要用,
// 第一次用到时请求,之后整个页面生命周期共享一份,切页面不重复拉。
import { ref, shallowRef } from 'vue'
import { corpusApi } from '../api/client'
import type { GroupCounts, GroupNode, MetaInfo } from '../api/types'

/** 板块固定顺序与中文名;后端以后新加的板块排在最后、直接显示原始 tag */
const GROUP_ORDER = ['java', 'database', 'architecture', 'tools', 'ai']
const GROUP_LABELS: Record<string, string> = {
  java: 'Java', database: '数据库', architecture: '架构设计', tools: '工具命令', ai: 'AI 大模型',
}

export function groupLabel(g: string | null | undefined): string {
  if (!g) return '全题库'
  return GROUP_LABELS[g] ?? g
}

export function sortGroups<T>(items: T[], key: (t: T) => string): T[] {
  const rank = (g: string) => {
    const i = GROUP_ORDER.indexOf(g)
    return i < 0 ? GROUP_ORDER.length : i
  }
  return [...items].sort((a, b) => rank(key(a)) - rank(key(b)))
}

// ── 服务状态 ──
export const meta = shallowRef<MetaInfo | null>(null)
export const metaFailed = ref(false)
let metaReq: Promise<void> | null = null

export function loadMeta(): Promise<void> {
  metaReq ??= corpusApi.meta()
    .then((m) => { meta.value = m; metaFailed.value = false })
    .catch(() => { metaFailed.value = true; metaReq = null }) // 失败了允许下次重试
  return metaReq
}

// ── 各板块题数 ──
export const groupCounts = shallowRef<GroupCounts>({})
let groupsReq: Promise<void> | null = null

export function loadGroups(): Promise<void> {
  groupsReq ??= corpusApi.groups()
    .then((g) => { groupCounts.value = g })
    .catch(() => { groupsReq = null })
  return groupsReq
}

// ── 知识库树(672 题的标题,几十 KB)──
export const tree = shallowRef<GroupNode[]>([])
export const treeFailed = ref(false)
let treeReq: Promise<void> | null = null

export function loadTree(): Promise<void> {
  treeReq ??= corpusApi.tree()
    .then((t) => { tree.value = sortGroups(t, (g) => g.group); treeFailed.value = false })
    .catch(() => { treeFailed.value = true; treeReq = null })
  return treeReq
}

/** 树压平成一维,知识库列表、上一题/下一题、问答页的推荐题都基于它 */
export interface FlatQuestion {
  id: number
  question: string
  group: string
  doc: string
  category: string | null
}

export function flatten(nodes: GroupNode[]): FlatQuestion[] {
  const out: FlatQuestion[] = []
  for (const g of nodes) {
    for (const d of g.docs) {
      for (const c of d.categories) {
        for (const q of c.questions) {
          out.push({ id: q.id, question: q.question, group: g.group, doc: d.title, category: c.category })
        }
      }
    }
  }
  return out
}
