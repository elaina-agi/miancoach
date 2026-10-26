<template>
  <div class="chat">
    <!-- 历史对话:宽屏常驻左侧(可收起),窄屏收进左侧滑出面板 -->
    <aside v-if="!isNarrow" v-show="!histCollapsed" class="hist-pane">
      <ChatHistory :sessions="sessions" :active-id="currentSessionId" :loading="sessionsLoading" :disabled="streaming"
                   @select="openSession" @remove="removeSession" @new="newChat" />
    </aside>
    <Sheet v-else v-model:open="histSheet" side="left" :width="300" label="历史对话">
      <ChatHistory :sessions="sessions" :active-id="currentSessionId" :loading="sessionsLoading" :disabled="streaming"
                   @select="(id) => { histSheet = false; openSession(id) }" @remove="removeSession"
                   @new="() => { histSheet = false; newChat() }" />
    </Sheet>

    <section class="conv">
      <header class="conv-head">
        <button class="btn btn-ghost btn-icon btn-sm" :title="histToggleTitle" :aria-label="histToggleTitle" @click="toggleHistory">
          <PanelLeftOpen v-if="isNarrow || histCollapsed" :size="18" />
          <PanelLeftClose v-else :size="18" />
        </button>
        <span class="conv-title">{{ convTitle }}</span>
        <button v-if="messages.length" class="btn btn-ghost btn-sm" :disabled="streaming" @click="newChat">
          <Plus :size="15" />新对话
        </button>
      </header>

      <!-- 空态:输入框居中,下面是推荐题 -->
      <div v-if="!messages.length" class="hero">
        <div v-if="loadingSession" class="hero-loading"><span class="dots"><i /><i /><i /></span>正在打开对话</div>
        <div v-else class="hero-inner">
          <BrandMark :size="40" />
          <h1>今天想搞懂哪道题？</h1>
          <p class="hero-sub">回答锚定 <b class="num">{{ meta?.totalChunks ?? '…' }}</b> 道题库原文，结论都标了出处，点开就能核对。</p>
          <Composer ref="composerEl" v-model="input" size="hero" autofocus :streaming="streaming" @send="send()" @stop="stop" />
          <div v-if="suggestions.length" class="suggest">
            <div class="suggest-head">
              <span>不知道问什么？从题库里挑几道</span>
              <button class="btn btn-ghost btn-sm" @click="reshuffle"><Shuffle :size="14" />换一批</button>
            </div>
            <div class="suggest-grid">
              <button v-for="s in suggestions" :key="s.id" class="suggest-card" @click="send(s.question)">
                <span class="sg-q">{{ s.question }}</span>
                <span v-if="s.group" class="sg-meta">{{ groupLabel(s.group) }} · {{ s.category ?? s.doc }}</span>
              </button>
            </div>
          </div>
        </div>
      </div>

      <template v-else>
        <div ref="threadEl" class="thread" @scroll.passive="onScroll">
          <div class="thread-inner">
            <article v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
              <div v-if="m.role === 'user'" class="bubble">{{ m.text }}</div>
              <template v-else>
                <div v-if="pendingLabel(m)" class="thinking" role="status">
                  <span class="dots"><i /><i /><i /></span>{{ pendingLabel(m) }}
                </div>
                <MarkdownView v-if="m.text" :content="m.text" citable :streaming="m.streaming"
                              @cite="(n) => openSource(m, n - 1)" />

                <div v-if="m.noAnswer" class="callout">
                  <Info :size="16" />
                  <div>
                    <strong>题库里没有能支撑回答的内容</strong>
                    <p>为了不编造，这题没有作答。换个问法试试，或者去知识库里搜一搜。</p>
                  </div>
                </div>
                <div v-if="m.error" class="callout bad">
                  <CircleAlert :size="16" />
                  <div>
                    <strong>回答中断了</strong>
                    <p>{{ m.error }}</p>
                  </div>
                  <button class="btn btn-secondary btn-sm" :disabled="streaming" @click="retry(i)"><RotateCcw :size="14" />重试</button>
                </div>

                <div v-if="m.refs?.length && !m.noAnswer" class="sources">
                  <span class="sources-label">来源</span>
                  <button v-for="(r, j) in m.refs" :key="r.chunkId" class="source-chip"
                          :class="{ on: srcOpen && srcRefs === m.refs && srcIndex === j }" :title="r.question"
                          @click="openSource(m, j)">
                    <span class="sc-n">{{ j + 1 }}</span><span class="sc-q">{{ r.question }}</span>
                  </button>
                </div>
                <div v-if="!m.streaming && m.text && !m.error" class="msg-actions">
                  <button class="btn btn-ghost btn-sm" @click="copy(m.text)"><Copy :size="14" />复制</button>
                </div>
              </template>
            </article>
            <!-- 最后一条是提问却没有回答:上次生成中途断开(刷新、关页面),后端只存下了提问 -->
            <div v-if="orphanQuestion" class="callout">
              <Info :size="16" />
              <div>
                <strong>这条提问没有收到回答</strong>
                <p>可能是上次生成到一半页面被关掉了。</p>
              </div>
              <button class="btn btn-secondary btn-sm" @click="send(orphanQuestion)"><RotateCcw :size="14" />重新提问</button>
            </div>
          </div>
        </div>

        <div class="dock">
          <div class="dock-inner">
            <Transition name="fade">
              <button v-if="!stick" class="to-bottom" aria-label="回到底部" @click="scrollBottom(true)">
                <ArrowDown :size="16" />
              </button>
            </Transition>
            <Composer ref="composerEl" v-model="input" :streaming="streaming" @send="send()" @stop="stop" />
            <p class="disclaimer">Enter 发送，Shift + Enter 换行 · AI 回答可能有偏差，重要结论请点来源核对原文</p>
          </div>
        </div>
      </template>
    </section>

    <!-- 来源原文:宽屏在右侧并排展开(对话不被遮住),窄屏从右侧滑出 -->
    <aside v-if="wideSource && srcOpen" class="src-pane">
      <SourcePanel v-model:index="srcIndex" :refs="srcRefs" @close="srcOpen = false" />
    </aside>
    <Sheet v-if="!wideSource" v-model:open="srcOpen" side="right" :width="560" label="来源原文">
      <SourcePanel v-model:index="srcIndex" :refs="srcRefs" @close="srcOpen = false" />
    </Sheet>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowDown, CircleAlert, Copy, Info, PanelLeftClose, PanelLeftOpen, Plus, RotateCcw, Shuffle,
} from 'lucide-vue-next'
import { askStream, chatApi } from '../api/client'
import type { AskRef, ChatMessageRow, ChatSessionRow } from '../api/types'
import BrandMark from '../components/BrandMark.vue'
import MarkdownView from '../components/MarkdownView.vue'
import Sheet from '../components/ui/Sheet.vue'
import Composer from '../components/chat/Composer.vue'
import ChatHistory from '../components/chat/ChatHistory.vue'
import SourcePanel from '../components/chat/SourcePanel.vue'
import { flatten, groupLabel, loadMeta, loadTree, meta, tree, type FlatQuestion } from '../lib/catalog'
import { confirm } from '../lib/confirm'
import { useMediaQuery } from '../lib/responsive'
import { createSmoothAppender, type SmoothAppender } from '../lib/smoothStream'
import { toast } from '../lib/toast'
import { useOverlay } from '../lib/overlay'

interface Msg {
  role: 'user' | 'assistant'
  text: string
  refs?: AskRef[]
  streaming?: boolean
  noAnswer?: boolean
  error?: string
}

const route = useRoute()
const router = useRouter()

const messages = ref<Msg[]>([])
const input = ref('')
const streaming = ref(false)
const loadingSession = ref(false)
const threadEl = ref<HTMLElement | null>(null)
const composerEl = ref<InstanceType<typeof Composer> | null>(null)
let abort: (() => void) | null = null
// 去抖动渲染器:和 abort 一样提到函数外,stop() 与组件卸载时要能停掉它
let appender: SmoothAppender | null = null

// ── 布局断点 ──
// ≤900px 历史收进滑出面板;≥1280px 来源原文在右侧并排展开,更窄时改成滑出
const isNarrow = useMediaQuery('(max-width: 900px)')
const wideSource = useMediaQuery('(min-width: 1280px)')

const HIST_KEY = 'miancoach-history-collapsed'
const histCollapsed = ref(localStorage.getItem(HIST_KEY) === '1')
const histSheet = ref(false)
const histToggleTitle = computed(() => (isNarrow.value || histCollapsed.value ? '展开历史对话' : '收起历史对话'))

function toggleHistory() {
  if (isNarrow.value) {
    histSheet.value = true
    return
  }
  histCollapsed.value = !histCollapsed.value
  localStorage.setItem(HIST_KEY, histCollapsed.value ? '1' : '0')
}

// ── 历史会话 ──
// currentSessionId 为 null 表示"还没建会话":第一条消息发出时才建,不留空会话
const sessions = ref<ChatSessionRow[]>([])
const sessionsLoading = ref(true)
const currentSessionId = ref<string | null>(null)

const convTitle = computed(() => {
  if (!messages.value.length) return '新对话'
  return sessions.value.find((s) => s.id === currentSessionId.value)?.title ?? messages.value[0].text
})

/** 拉会话列表。每轮问答结束后都调一次——标题和"最后活跃时间"都变了。 */
async function refreshSessions() {
  try {
    sessions.value = await chatApi.sessions()
  } catch {
    // 历史拉不到不该影响问答主流程,这里不打扰用户;真出问题会在下一次操作时暴露
  } finally {
    sessionsLoading.value = false
  }
}

/** 后端消息 → 界面消息(字段名不同,且后端省略了 noAnswer=false)。 */
function toMsg(r: ChatMessageRow): Msg {
  return { role: r.role, text: r.content, refs: r.refs, noAnswer: r.noAnswer || undefined }
}

// 会话 id 在 URL 里(/chat/:sessionId):点历史、新对话都只改 URL,由这里统一加载,刷新页面也能回到原对话
async function syncFromRoute() {
  const id = (route.params.sessionId as string | undefined) || null
  if (id === currentSessionId.value) return
  if (streaming.value) stop() // 生成中切走:先停掉,免得流继续往别的会话里写
  if (!id) {
    resetChat()
    return
  }
  srcOpen.value = false
  loadingSession.value = true
  messages.value = []
  try {
    const rows = await chatApi.messages(id)
    messages.value = rows.map(toMsg)
    currentSessionId.value = id
    stick.value = true
    scrollBottom(true, 'auto')
  } catch (e) {
    toast.error('对话打开失败：' + (e as Error).message)
    void router.replace('/chat')
  } finally {
    loadingSession.value = false
  }
}

function resetChat() {
  messages.value = []
  currentSessionId.value = null
  srcOpen.value = false
  void nextTick(() => composerEl.value?.focus())
}

function openSession(id: string) {
  if (streaming.value) return
  void router.push(`/chat/${id}`)
}

function newChat() {
  if (streaming.value) return
  // 会话建失败时 URL 里没有 id,但界面上有消息:这种情况直接清空,不经过路由
  if (route.params.sessionId) void router.push('/chat')
  else resetChat()
}

async function removeSession(s: ChatSessionRow) {
  if (streaming.value) return
  const ok = await confirm({ title: '删除这条对话？', message: `「${s.title}」的全部问答会一起删除，不能恢复。`, confirmText: '删除', danger: true })
  if (!ok) return
  try {
    await chatApi.remove(s.id)
    // 删的正好是当前打开的 → 回到新对话,否则会继续往一个已不存在的会话里写
    if (currentSessionId.value === s.id) {
      currentSessionId.value = null
      messages.value = []
      void router.replace('/chat')
    }
    await refreshSessions()
    toast.success('已删除')
  } catch (e) {
    toast.error('删除失败：' + (e as Error).message)
  }
}

const orphanQuestion = computed(() => {
  const last = messages.value[messages.value.length - 1]
  return !streaming.value && !loadingSession.value && last?.role === 'user' ? last.text : null
})

// ── 推荐题 ──
const suggestions = shallowRef<FlatQuestion[]>([])
const FALLBACK = ['String 为什么不可变？', 'HashMap 的底层原理是什么？', 'Redis 缓存穿透怎么解决？', 'RAG 的检索效果怎么评测？']

/** 从题库随机挑 4 道,尽量来自不同板块,让人一眼看出能问哪些方向 */
function reshuffle() {
  const all = flatten(tree.value)
  if (!all.length) {
    suggestions.value = FALLBACK.map((q, i) => ({ id: -i - 1, question: q, group: '', doc: '', category: null }))
    return
  }
  const pool = [...all].sort(() => Math.random() - 0.5)
  const picked: FlatQuestion[] = []
  const usedGroups = new Set<string>()
  for (const q of pool) {
    if (picked.length >= 4) break
    if (!usedGroups.has(q.group) || usedGroups.size >= 5) {
      picked.push(q)
      usedGroups.add(q.group)
    }
  }
  suggestions.value = picked
}

// ── 来源原文 ──
const srcOpen = ref(false)
const srcRefs = shallowRef<AskRef[]>([])
const srcIndex = ref(0)
// 并排展开时不是遮罩层,也支持 Esc 关闭
useOverlay(computed(() => srcOpen.value && wideSource.value), () => { srcOpen.value = false }, false)

function openSource(m: Msg, i: number) {
  if (!m.refs?.length || i < 0 || i >= m.refs.length) return
  srcRefs.value = m.refs
  srcIndex.value = i
  srcOpen.value = true
}

// ── 滚动:用户往上翻着看时不要把他拽回底部 ──
const stick = ref(true)

function onScroll() {
  const el = threadEl.value
  if (!el) return
  stick.value = el.scrollHeight - el.scrollTop - el.clientHeight < 80
}

function scrollBottom(force = false, behavior: ScrollBehavior = 'smooth') {
  if (!force && !stick.value) return
  void nextTick(() => {
    const el = threadEl.value
    if (el) el.scrollTo({ top: el.scrollHeight, behavior })
  })
}

/**
 * 等待期的状态文案。**派生自现有状态,不新增 phase 字段**——
 * 加了字段就要在 onDone/onError/stop 三处记得清空,漏一处状态就卡住;
 * 派生的话条件不成立自动消失,不可能不同步。
 *   还没 refs           → 检索题库中(含多轮追问改写那一步,用户不需要区分)
 *   有 refs 但还没出字   → 已找到 n 篇材料,正在组织回答
 *   出字了 / 流已结束    → null,状态消失
 */
function pendingLabel(m: Msg): string | null {
  if (!m.streaming || m.text.length > 0) return null
  return m.refs?.length ? `找到 ${m.refs.length} 篇相关原文，正在组织回答` : '正在检索题库'
}

// ── 问答 ──
async function send(text?: string) {
  const q = (text ?? input.value).trim()
  if (!q || streaming.value) return
  input.value = ''
  // 取上一轮问答当 history(后端据此把追问里的指代改写成独立问题再检索)
  let history: { q: string; a: string }[] = []
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role === 'assistant' && !m.streaming && m.text && !m.error) {
      const prev = messages.value[i - 1]
      if (prev && prev.role === 'user') history = [{ q: prev.text, a: m.text }]
      break
    }
  }

  messages.value.push({ role: 'user', text: q })
  messages.value.push({ role: 'assistant', text: '', refs: [], streaming: true })
  // ★ 必须从数组里"读回来"拿到响应式代理。ref([]) 是深层响应式,但 push 进去的是原始对象,
  //   数组只在读取时才把它包成 proxy —— 直接改 push 前的那个局部变量会绕过 set 陷阱,
  //   流式期间一个字都不会渲染,直到 streaming.value=false 触发重渲染才整段刷出来。
  const msg = messages.value[messages.value.length - 1]
  streaming.value = true
  stick.value = true
  scrollBottom(true)

  // 本轮是新对话 → 先建会话。拿不到 id 就只影响历史记录,问答继续(和重排降级同一个思路)。
  if (!currentSessionId.value) {
    try {
      const id = (await chatApi.createSession(q)).sessionId
      currentSessionId.value = id
      // 只改 URL 不重新加载:syncFromRoute 看到 id 已经一致会直接返回
      void router.replace(`/chat/${id}`)
    } catch (e) {
      toast.info('会话创建失败，这轮问答不会进历史：' + (e as Error).message)
    }
  }
  if (!streaming.value) return // 建会话期间用户点了停止

  // delta 的到达间隔很不均匀(实测 0~574ms / 单块 0~47 字),直接 append 会一顿一顿。
  // 改成"入队 + 按帧匀速出队",真正写进 msg.text 的时机由 rAF 控制。
  appender = createSmoothAppender((chunk) => {
    msg.text += chunk
    scrollBottom()
  })
  abort = askStream(q, {
    onRefs: (refs) => {
      msg.refs = refs
      scrollBottom()
    },
    onDelta: (t) => {
      appender?.push(t)
    },
    onDone: (info) => {
      msg.noAnswer = info?.noAnswer
      // 收尾挂在"缓冲区排空后",不是挂在 done 上——
      // 否则最后一两百毫秒里字还在画,光标已经停了、按钮已经解锁了。
      appender?.finish(() => {
        msg.streaming = false
        streaming.value = false
        scrollBottom()
        void refreshSessions() // 标题/活跃时间都变了,刷新侧栏
      })
    },
    onError: (e) => {
      appender?.flushNow() // 出错就别慢慢画了,剩余一次性吐完
      msg.streaming = false
      msg.error = e
      streaming.value = false
      void refreshSessions() // 提问已经落库了(后端在流开始前就存),列表上有它
    },
  }, history, currentSessionId.value ?? undefined)
}

/** 出错的那一轮:把这一问一答从界面上拿掉,原问题重发 */
function retry(i: number) {
  const q = messages.value[i - 1]?.text
  if (!q || streaming.value) return
  messages.value.splice(i - 1, 2)
  void send(q)
}

function stop() {
  abort?.()
  appender?.cancel() // 停掉 rAF 循环,丢弃还没画出来的字
  abort = null
  const last = messages.value[messages.value.length - 1]
  if (last?.streaming) {
    last.streaming = false
    if (!last.text) last.text = '_已停止生成_'
  }
  streaming.value = false
}

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    toast.success('已复制到剪贴板')
  } catch {
    toast.error('复制失败，浏览器没有给剪贴板权限')
  }
}

watch(() => route.params.sessionId, () => { void syncFromRoute() })

onMounted(() => {
  void refreshSessions()
  void loadMeta()
  // 树拉不到(后端没起)时 reshuffle 会退回几道固定的示例题
  void loadTree().then(reshuffle)
  // 从知识库「问 AI」跳过来:问题预填进输入框,让用户确认或改写后再发
  const q = route.query.q
  if (typeof q === 'string' && q.trim()) {
    input.value = q
    void router.replace({ path: route.path, query: {} })
  }
  void syncFromRoute()
})

onBeforeUnmount(() => {
  abort?.()
  appender?.cancel() // 否则 rAF 回调会继续操作已卸载组件的状态
})
</script>

<style scoped>
.chat { height: 100%; display: flex; min-height: 0; }

.hist-pane { width: var(--side-w); flex: none; border-right: 1px solid var(--line); min-height: 0; }

.conv { flex: 1; min-width: 0; display: flex; flex-direction: column; min-height: 0; }
.conv-head { flex: none; display: flex; align-items: center; gap: 8px; height: 48px; padding: 0 12px; }
.conv-title { flex: 1; min-width: 0; font-size: 14px; font-weight: 500; color: var(--ink-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 空态 */
.hero { flex: 1; min-height: 0; overflow-y: auto; display: flex; padding: 0 24px 40px; }
.hero-inner { width: 100%; max-width: 720px; margin: auto; padding-top: 24px; display: flex; flex-direction: column; align-items: center; animation: fade-up 320ms var(--ease); }
.hero-inner h1 { margin-top: 18px; font-size: 28px; letter-spacing: -0.02em; }
.hero-sub { margin: 10px 0 28px; color: var(--ink-2); font-size: 15px; text-align: center; text-wrap: balance; }
.hero-sub b { color: var(--ink); font-weight: 600; }
.hero-inner > .composer { width: 100%; }
.hero-loading { margin: auto; display: flex; align-items: center; gap: 10px; color: var(--ink-3); }

.suggest { width: 100%; margin-top: 28px; }
.suggest-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; font-size: 13px; color: var(--ink-3); }
.suggest-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.suggest-card { display: flex; flex-direction: column; gap: 6px; padding: 14px 16px; text-align: left; border: 1px solid var(--line); border-radius: var(--r-lg); background: transparent; cursor: pointer; transition: background var(--t) var(--ease), border-color var(--t) var(--ease); }
.suggest-card:hover { background: var(--surface); border-color: var(--line-strong); }
.sg-q { font-size: 14px; font-weight: 500; line-height: 1.5; color: var(--ink); }
.sg-meta { font-size: 12px; color: var(--ink-3); }

/* 对话流 */
/* 顶部 16px 渐隐:滚上去的内容不会在标题栏下面被"一刀切" */
.thread { flex: 1; min-height: 0; overflow-y: auto; mask-image: linear-gradient(to bottom, transparent 0, #000 16px); }
.thread-inner { max-width: 768px; margin: 0 auto; padding: 12px 28px 28px; display: flex; flex-direction: column; gap: 28px; }
.msg { min-width: 0; animation: fade-up 220ms var(--ease); }
.msg.user { display: flex; justify-content: flex-end; }
.bubble { max-width: 82%; padding: 10px 16px; border-radius: 18px 18px 4px 18px; background: var(--fill-strong); color: var(--ink); font-size: 15px; line-height: 1.7; white-space: pre-wrap; overflow-wrap: anywhere; }

.thinking { display: inline-flex; align-items: center; gap: 10px; height: 28px; color: var(--ink-3); font-size: 14px; }
.thinking .dots { color: var(--accent); }

.callout { margin-top: 12px; display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; border-radius: var(--r); background: var(--fill); color: var(--ink-2); font-size: 13.5px; line-height: 1.6; }
.callout > svg { margin-top: 3px; flex: none; color: var(--ink-3); }
.callout strong { display: block; color: var(--ink); font-weight: 600; }
.callout div { flex: 1; min-width: 0; }
.callout.bad { background: var(--bad-soft); }
.callout.bad > svg { color: var(--bad); }

.sources { margin-top: 16px; display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.sources-label { font-size: 12.5px; color: var(--ink-3); margin-right: 4px; }
.source-chip { max-width: 260px; display: inline-flex; align-items: center; gap: 7px; height: 30px; padding: 0 10px 0 4px; border: 1px solid var(--line); border-radius: 999px; background: var(--surface); color: var(--ink-2); font-size: 12.5px; cursor: pointer; transition: border-color var(--t) var(--ease), color var(--t) var(--ease), background var(--t) var(--ease); }
.source-chip:hover { border-color: var(--line-strong); color: var(--ink); }
.source-chip.on { border-color: var(--accent-line); background: var(--accent-soft); color: var(--ink); }
.sc-n { flex: none; width: 22px; height: 22px; display: grid; place-items: center; border-radius: 50%; background: var(--fill); font-size: 11.5px; font-weight: 650; font-variant-numeric: tabular-nums; }
.source-chip.on .sc-n { background: var(--accent); color: var(--accent-ink); }
.sc-q { min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.msg-actions { margin-top: 8px; margin-left: -10px; display: flex; gap: 2px; }
.msg-actions .btn { color: var(--ink-3); }
.msg-actions .btn:hover { color: var(--ink); }

/* 输入区 */
.dock { flex: none; padding: 0 28px 14px; }
.dock-inner { position: relative; max-width: 768px; margin: 0 auto; }
.disclaimer { margin-top: 8px; text-align: center; font-size: 12px; color: var(--ink-3); }
.to-bottom { position: absolute; top: -48px; left: 50%; transform: translateX(-50%); width: 34px; height: 34px; display: grid; place-items: center; border: 1px solid var(--line-strong); border-radius: 50%; background: var(--surface); color: var(--ink-2); box-shadow: var(--shadow-pop); cursor: pointer; }
.to-bottom:hover { color: var(--ink); }
.fade-enter-active, .fade-leave-active { transition: opacity 160ms var(--ease); }
.fade-enter-from, .fade-leave-to { opacity: 0; }

.src-pane { width: 460px; flex: none; border-left: 1px solid var(--line); min-height: 0; }

@media (max-width: 720px) {
  .conv-head { height: 44px; padding: 0 8px; }
  .hero { padding: 0 16px 24px; }
  .hero-inner { padding-top: 8px; }
  .hero-inner h1 { font-size: 22px; margin-top: 14px; }
  .hero-sub { font-size: 14px; margin-bottom: 20px; }
  .suggest-grid { grid-template-columns: 1fr; }
  .thread-inner { padding: 8px 16px 24px; gap: 24px; }
  .bubble { max-width: 90%; }
  .dock { padding: 0 12px 10px; }
  .disclaimer { display: none; }
  /* 来源在手机上改成一行横向滑动,不然 5 篇就占满一屏 */
  .sources { flex-wrap: nowrap; overflow-x: auto; scrollbar-width: none; margin-right: -16px; padding-right: 16px; }
  .sources::-webkit-scrollbar { display: none; }
  .sources-label { flex: none; }
  .source-chip { flex: none; max-width: 240px; }
}
</style>
