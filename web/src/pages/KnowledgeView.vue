<template>
  <div class="kb" :class="{ reading: currentId !== null }">
    <!-- 左:题目列表 -->
    <aside class="list-pane">
      <div class="list-top">
        <label class="search">
          <Search :size="16" class="search-icon" />
          <input ref="searchEl" v-model="kw" class="input" type="search" placeholder="搜索题目" aria-label="搜索题目" />
          <button v-if="kw" class="search-clear" aria-label="清空搜索" @click="kw = ''; searchEl?.focus()"><X :size="14" /></button>
        </label>
        <div class="groups" role="tablist" aria-label="按板块筛选">
          <button role="tab" class="g-chip" :class="{ on: !group }" :aria-selected="!group" @click="group = ''">
            全部<span class="num">{{ all.length }}</span>
          </button>
          <button v-for="g in groupStats" :key="g.group" role="tab" class="g-chip" :class="{ on: group === g.group }"
                  :aria-selected="group === g.group" @click="group = g.group">
            {{ groupLabel(g.group) }}<span class="num">{{ g.count }}</span>
          </button>
        </div>
      </div>

      <div ref="listEl" class="list-scroll">
        <div v-if="!tree.length && !treeFailed" class="list-skel">
          <div v-for="i in 10" :key="i" class="skeleton" :style="{ width: 60 + (i * 37) % 35 + '%' }" />
        </div>
        <div v-else-if="treeFailed" class="list-empty">
          题库加载失败。<button class="link" @click="retryTree">重试</button>
        </div>

        <!-- 搜索结果:压平显示,命中处高亮 -->
        <template v-else-if="kw.trim()">
          <p class="list-count">找到 <b class="num">{{ visible.length }}</b> 道题</p>
          <RouterLink v-for="q in visible" :key="q.id" :to="`/kb/${q.id}`" class="q-item" :class="{ on: q.id === currentId }"
                      :data-qid="q.id">
            <span class="q-text"><template v-for="(seg, i) in highlight(q.question)" :key="i"><mark v-if="seg.hit">{{ seg.t }}</mark><template v-else>{{ seg.t }}</template></template></span>
            <span class="q-meta">{{ groupLabel(q.group) }} · {{ q.category ?? q.doc }}</span>
          </RouterLink>
          <p v-if="!visible.length" class="list-empty">没有匹配的题。换个关键词，或者<RouterLink :to="{ path: '/chat', query: { q: kw } }" class="link">直接问 AI</RouterLink></p>
        </template>

        <!-- 浏览:按 文档 → 类目 分组,文档标题吸顶 -->
        <template v-else>
          <section v-for="d in docs" :key="d.key" class="doc">
            <h3 class="doc-title"><span>{{ d.title }}</span><span class="num">{{ d.count }}</span></h3>
            <template v-for="c in d.cats" :key="c.name ?? ''">
              <h4 v-if="c.name" class="cat-title">{{ c.name }}</h4>
              <RouterLink v-for="q in c.items" :key="q.id" :to="`/kb/${q.id}`" class="q-item" :class="{ on: q.id === currentId }"
                          :data-qid="q.id">
                <span class="q-text">{{ q.question }}</span>
              </RouterLink>
            </template>
          </section>
        </template>
      </div>
    </aside>

    <!-- 右:阅读 -->
    <section ref="readerEl" class="reader">
      <div v-if="currentId === null" class="reader-empty">
        <BookOpen :size="28" />
        <h2>挑一道题开始读</h2>
        <p>左边按板块浏览，或者搜关键词。<br>读的时候用 <span class="kbd">←</span> <span class="kbd">→</span> 翻上一题、下一题。</p>
        <button class="btn btn-secondary" :disabled="!all.length" @click="randomPick"><Shuffle :size="15" />随机来一题</button>
      </div>

      <article v-else class="paper">
        <button class="btn btn-ghost btn-sm back" @click="router.push('/kb')"><ChevronLeft :size="16" />题目列表</button>
        <nav v-if="currentQ" class="crumbs" aria-label="位置">
          <span>{{ groupLabel(currentQ.group) }}</span><ChevronRight :size="13" />
          <span>{{ currentQ.doc }}</span>
          <template v-if="currentQ.category"><ChevronRight :size="13" /><span>{{ currentQ.category }}</span></template>
        </nav>
        <h1 class="title">{{ detail?.question ?? currentQ?.question ?? '' }}</h1>
        <div class="actions">
          <RouterLink v-if="detail" :to="{ path: '/chat', query: { q: detail.question } }" class="btn btn-secondary btn-sm">
            <MessageSquare :size="14" />问 AI
          </RouterLink>
          <RouterLink to="/quiz" class="btn btn-ghost btn-sm"><NotebookPen :size="14" />去模拟面试</RouterLink>
          <a v-if="detail?.url" :href="detail.url" target="_blank" rel="noopener noreferrer" class="btn btn-ghost btn-sm">
            原站文章<ExternalLink :size="13" />
          </a>
        </div>

        <MarkdownView v-if="detail" :content="detail.content" class="body" />
        <div v-else-if="detailFailed" class="body fail">这道题加载失败。<button class="link" @click="loadDetail">重试</button></div>
        <div v-else class="body skel">
          <div class="skeleton" style="width: 94%" /><div class="skeleton" style="width: 100%" /><div class="skeleton" style="width: 72%" />
          <div class="skeleton" style="width: 88%; margin-top: 12px" /><div class="skeleton" style="width: 96%" />
        </div>

        <nav class="pager" aria-label="翻页">
          <RouterLink v-if="prevQ" :to="`/kb/${prevQ.id}`" class="pg prev">
            <span class="pg-dir"><ArrowLeft :size="14" />上一题</span><span class="pg-q">{{ prevQ.question }}</span>
          </RouterLink>
          <span v-else class="pg-ph" />
          <RouterLink v-if="nextQ" :to="`/kb/${nextQ.id}`" class="pg next">
            <span class="pg-dir">下一题<ArrowRight :size="14" /></span><span class="pg-q">{{ nextQ.question }}</span>
          </RouterLink>
        </nav>
      </article>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, ArrowRight, BookOpen, ChevronLeft, ChevronRight, ExternalLink, MessageSquare, NotebookPen, Search, Shuffle, X,
} from 'lucide-vue-next'
import MarkdownView from '../components/MarkdownView.vue'
import { corpusApi } from '../api/client'
import type { ChunkFull } from '../api/types'
import { flatten, groupLabel, loadTree, tree, treeFailed, type FlatQuestion } from '../lib/catalog'

const route = useRoute()
const router = useRouter()

const kw = ref('')
const group = ref('')
const searchEl = ref<HTMLInputElement | null>(null)
const listEl = ref<HTMLElement | null>(null)
const readerEl = ref<HTMLElement | null>(null)

const all = computed(() => flatten(tree.value))
const byId = computed(() => new Map(all.value.map((q) => [q.id, q])))

const groupStats = computed(() => tree.value.map((g) => ({
  group: g.group,
  count: g.docs.reduce((s, d) => s + d.categories.reduce((s2, c) => s2 + c.questions.length, 0), 0),
})))

/** 当前列表里看得到的题(按板块筛选 + 关键词),上一题/下一题也按这个顺序走 */
const visible = computed(() => {
  const k = kw.value.trim().toLowerCase()
  return all.value.filter((q) => (!group.value || q.group === group.value)
    && (!k || q.question.toLowerCase().includes(k) || (q.category ?? '').toLowerCase().includes(k)))
})

/** 浏览模式按 文档 → 类目 两级分组 */
const docs = computed(() => {
  const out: Array<{ key: string; title: string; count: number; cats: Array<{ name: string | null; items: FlatQuestion[] }> }> = []
  for (const q of visible.value) {
    const key = q.group + '/' + q.doc
    let d = out[out.length - 1]
    if (!d || d.key !== key) out.push(d = { key, title: q.doc, count: 0, cats: [] })
    let c = d.cats[d.cats.length - 1]
    if (!c || c.name !== q.category) d.cats.push(c = { name: q.category, items: [] })
    c.items.push(q)
    d.count++
  }
  return out
})

function highlight(text: string): Array<{ t: string; hit: boolean }> {
  const k = kw.value.trim()
  if (!k) return [{ t: text, hit: false }]
  const out: Array<{ t: string; hit: boolean }> = []
  const lower = text.toLowerCase()
  const lk = k.toLowerCase()
  let i = 0
  for (let j = lower.indexOf(lk); j >= 0; j = lower.indexOf(lk, i)) {
    if (j > i) out.push({ t: text.slice(i, j), hit: false })
    out.push({ t: text.slice(j, j + k.length), hit: true })
    i = j + k.length
  }
  if (i < text.length) out.push({ t: text.slice(i), hit: false })
  return out
}

// ── 当前题 ──
const currentId = computed(() => {
  const v = Number(route.params.id)
  return Number.isFinite(v) && v > 0 ? v : null
})
const currentQ = computed(() => (currentId.value === null ? null : byId.value.get(currentId.value) ?? null))

/** 翻页顺序:当前题在可见列表里就按可见列表,被筛选掉了就按全库顺序 */
const order = computed(() => (currentId.value !== null && visible.value.some((q) => q.id === currentId.value) ? visible.value : all.value))
const pos = computed(() => order.value.findIndex((q) => q.id === currentId.value))
const prevQ = computed(() => (pos.value > 0 ? order.value[pos.value - 1] : null))
const nextQ = computed(() => (pos.value >= 0 && pos.value < order.value.length - 1 ? order.value[pos.value + 1] : null))

// 原文按 id 缓存;读当前题时顺手预取下一题,翻页几乎无等待
const cache = new Map<number, ChunkFull>()
const detail = shallowRef<ChunkFull | null>(null)
const detailFailed = ref(false)

async function fetchChunk(id: number): Promise<ChunkFull> {
  const hit = cache.get(id)
  if (hit) return hit
  const d = await corpusApi.chunk(id)
  cache.set(id, d)
  return d
}

async function loadDetail() {
  const id = currentId.value
  detailFailed.value = false
  if (id === null) { detail.value = null; return }
  detail.value = cache.get(id) ?? null
  if (!detail.value) {
    try {
      const d = await fetchChunk(id)
      if (currentId.value === id) detail.value = d
    } catch {
      if (currentId.value === id) detailFailed.value = true
      return
    }
  }
  if (nextQ.value) void fetchChunk(nextQ.value.id).catch(() => {})
}

function randomPick() {
  const pool = visible.value.length ? visible.value : all.value
  if (pool.length) void router.push(`/kb/${pool[Math.floor(Math.random() * pool.length)].id}`)
}

function retryTree() {
  void loadTree()
}

/**
 * 把列表里的当前题滚进视野(从别处直接打开链接、或用键盘翻页时)。
 * 窄屏从阅读返回列表时 currentId 已经是 null,列表刚从 display:none 恢复、滚动位置丢了,
 * 这时定位到刚才读的那道题,不用从头找
 */
let lastViewed: number | null = null
function revealInList() {
  if (currentId.value !== null) lastViewed = currentId.value
  void nextTick(() => {
    const el = listEl.value?.querySelector<HTMLElement>(`[data-qid="${currentId.value ?? lastViewed}"]`)
    el?.scrollIntoView({ block: currentId.value === null ? 'center' : 'nearest' })
  })
}

watch(currentId, () => {
  void loadDetail()
  readerEl.value?.scrollTo({ top: 0 })
  revealInList()
})
watch(() => tree.value.length, () => { revealInList(); if (nextQ.value) void fetchChunk(nextQ.value.id).catch(() => {}) })

// ← → 翻页;正在输入时不拦截(搜索框里要能移动光标)
function onKey(e: KeyboardEvent) {
  const t = e.target as HTMLElement
  if (t.closest('input, textarea, [contenteditable]') || e.metaKey || e.ctrlKey || e.altKey) return
  if (e.key === 'ArrowLeft' && prevQ.value) void router.push(`/kb/${prevQ.value.id}`)
  else if (e.key === 'ArrowRight' && nextQ.value) void router.push(`/kb/${nextQ.value.id}`)
  else if (e.key === '/') { e.preventDefault(); searchEl.value?.focus() }
}

onMounted(() => {
  void loadTree()
  void loadDetail()
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
</script>

<style scoped>
.kb { height: 100%; display: flex; min-height: 0; }

.list-pane { width: var(--side-w); flex: none; display: flex; flex-direction: column; min-height: 0; border-right: 1px solid var(--line); }
.list-top { flex: none; padding: 12px 12px 10px; display: flex; flex-direction: column; gap: 10px; border-bottom: 1px solid var(--line); }
.search { position: relative; display: block; }
.search .input { padding-left: 36px; padding-right: 32px; }
.search .input::-webkit-search-cancel-button { display: none; }
.search-icon { position: absolute; left: 12px; top: 50%; transform: translateY(-50%); color: var(--ink-3); pointer-events: none; }
.search-clear { position: absolute; right: 6px; top: 50%; transform: translateY(-50%); width: 26px; height: 26px; display: grid; place-items: center; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--ink-3); cursor: pointer; }
.search-clear:hover { background: var(--fill); color: var(--ink); }
.groups { display: flex; flex-wrap: wrap; gap: 6px; }
.g-chip { display: inline-flex; align-items: center; gap: 5px; height: 28px; padding: 0 10px; border: 1px solid var(--line); border-radius: 999px; background: transparent; color: var(--ink-2); font-size: 12.5px; font-weight: 500; cursor: pointer; transition: all var(--t) var(--ease); }
.g-chip .num { color: var(--ink-3); font-size: 11.5px; }
.g-chip:hover { border-color: var(--line-strong); color: var(--ink); }
.g-chip.on { background: var(--ink); border-color: var(--ink); color: var(--bg); }
.g-chip.on .num { color: color-mix(in srgb, var(--bg) 70%, transparent); }

.list-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 0 8px 24px; }
.list-skel { padding: 16px 8px; display: flex; flex-direction: column; gap: 14px; }
.list-skel .skeleton { height: 14px; }
.list-count { padding: 12px 8px 6px; font-size: 12.5px; color: var(--ink-3); }
.list-count b { color: var(--ink); font-weight: 600; }
.list-empty { padding: 24px 8px; font-size: 13px; color: var(--ink-3); line-height: 1.8; }
.link { border: 0; background: none; padding: 0; color: var(--accent); cursor: pointer; font: inherit; font-weight: 500; }

.doc-title { position: sticky; top: 0; z-index: 1; display: flex; justify-content: space-between; align-items: center; margin: 0 -8px; padding: 14px 16px 6px; background: var(--bg); font-size: 12.5px; font-weight: 600; color: var(--ink); }
.doc-title .num { font-weight: 500; color: var(--ink-3); }
.cat-title { padding: 10px 8px 4px; font-size: 12px; font-weight: 500; color: var(--ink-3); }
.q-item { display: flex; flex-direction: column; gap: 2px; padding: 7px 10px; border-radius: var(--r); color: var(--ink-2); font-size: 13.5px; line-height: 1.55; transition: background var(--t) var(--ease), color var(--t) var(--ease); }
.q-item:hover { background: var(--fill); color: var(--ink); }
.q-item.on { background: var(--accent-soft); color: var(--ink); }
.q-item.on .q-text { font-weight: 500; }
.q-meta { font-size: 12px; color: var(--ink-3); }
.q-text mark { background: color-mix(in srgb, var(--mid) 22%, transparent); color: inherit; border-radius: 3px; padding: 0 1px; }

.reader { flex: 1; min-width: 0; overflow-y: auto; }
.reader-empty { height: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; padding: 24px; text-align: center; color: var(--ink-3); }
.reader-empty h2 { margin-top: 6px; font-size: 18px; color: var(--ink); }
.reader-empty p { max-width: 420px; font-size: 13.5px; line-height: 1.8; margin-bottom: 8px; }

.paper { max-width: 760px; margin: 0 auto; padding: 36px 40px 56px; animation: fade-up 220ms var(--ease); }
.back { display: none; margin: -8px 0 12px -10px; }
.crumbs { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; font-size: 13px; color: var(--ink-3); }
.title { margin-top: 10px; font-size: 26px; line-height: 1.45; letter-spacing: -0.015em; text-wrap: balance; }
.actions { display: flex; flex-wrap: wrap; gap: 6px; margin: 16px 0 0 -2px; }
.body { margin-top: 28px; padding-top: 28px; border-top: 1px solid var(--line); }
.skel { display: flex; flex-direction: column; gap: 12px; }
.skel .skeleton { height: 15px; }
.fail { color: var(--ink-2); }

.pager { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-top: 48px; }
.pg { display: flex; flex-direction: column; gap: 6px; padding: 14px 16px; border: 1px solid var(--line); border-radius: var(--r-lg); transition: border-color var(--t) var(--ease), background var(--t) var(--ease); min-width: 0; }
.pg:hover { border-color: var(--line-strong); background: var(--surface); }
.pg.next { text-align: right; align-items: flex-end; }
.pg-dir { display: inline-flex; align-items: center; gap: 4px; font-size: 12.5px; color: var(--ink-3); }
.pg-q { font-size: 14px; font-weight: 500; line-height: 1.5; color: var(--ink); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

/* 窄屏:列表和阅读二选一,URL 里有题号就显示阅读,返回按钮回列表 */
@media (max-width: 860px) {
  .list-pane { width: 100%; border-right: 0; }
  .kb.reading .list-pane { display: none; }
  .kb:not(.reading) .reader { display: none; }
  .back { display: inline-flex; }
  .paper { padding: 20px 18px 40px; }
  .title { font-size: 21px; }
  .body { margin-top: 20px; padding-top: 20px; }
  .pager { grid-template-columns: 1fr; margin-top: 32px; }
  .pg.next { text-align: left; align-items: flex-start; }
}
</style>
