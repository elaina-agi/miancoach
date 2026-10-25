<template>
  <div class="chat-layout">
    <!-- 二级侧栏:历史会话。桌面端内嵌在左;窄屏(≤980px)挪进抽屉,从顶栏按钮唤出 -->
    <el-drawer v-if="isNarrow" v-model="histOpen" direction="ltr" :size="min(300, windowW * 0.82)"
               :with-header="false" class="hist-drawer">
      <HistoryRail :sessions="sessions" :active-id="currentSessionId" :disabled="streaming"
                   @select="selectFromDrawer" @remove="removeSession" @new="newFromDrawer" />
    </el-drawer>
    <HistoryRail v-else :sessions="sessions" :active-id="currentSessionId" :disabled="streaming"
                 @select="loadSession" @remove="removeSession" @new="newSession" />

    <div class="page chat-page">
      <!-- 顶部 -->
      <header class="page-head">
        <div>
          <h1>面试问答</h1>
          <p class="sub">用自己的话提问,答案锚定权威题库原文,可追到出处复习。</p>
        </div>
        <button v-if="isNarrow" class="hist-toggle" @click="histOpen = true">
          <el-icon :size="14"><Clock /></el-icon>
          历史会话
        </button>
      </header>

      <!-- 空态快捷话题 -->
      <div v-if="messages.length === 0" class="empty">
        <p class="empty-hint">随便问一道,或从高频考点开始:</p>
        <div class="topic-grid">
          <button v-for="t in topics" :key="t" class="topic" @click="send(t)">{{ t }}</button>
        </div>
      </div>

      <!-- 对话流 -->
      <div ref="listEl" class="thread">
        <div v-for="(m, i) in messages" :key="i" class="row" :class="m.role">
          <div class="bubble" :class="m.role">
            <template v-if="m.role === 'assistant'">
              <MarkdownView v-if="m.text" :content="m.text" />
              <!-- 等待期状态:检索中 / 组织回答中。派生自 streaming+refs+text -->
              <div v-if="pendingLabel(m)" class="status">
                <span class="status-dot" />{{ pendingLabel(m) }}
              </div>
              <div v-if="m.streaming && m.text" class="cursor" />
              <div v-if="m.noAnswer" class="no-answer">语料外问题 · 已拒绝作答</div>
              <!-- 引用 -->
              <div v-if="m.refs?.length" class="refs">
                <div class="refs-title">出处 {{ m.refs.length }} 篇 · 点击追读原文</div>
                <div class="refs-grid">
                  <button v-for="(r, j) in m.refs" :key="r.chunkId" class="ref" @click="openRef(r)">
                    <span class="ref-idx">{{ j + 1 }}</span>
                    <span class="ref-body">
                      <span class="ref-q">{{ r.question }}</span>
                      <span class="ref-meta">{{ r.group }} · {{ r.title }}</span>
                    </span>
                  </button>
                </div>
              </div>
            </template>
            <template v-else>
              <p class="user-text">{{ m.text }}</p>
            </template>
          </div>
        </div>
      </div>

      <!-- 输入 -->
      <div class="composer-wrap">
        <div class="composer">
          <el-input v-model="input" type="textarea" :autosize="{ minRows: 1, maxRows: 5 }"
                    resize="none" placeholder="输入问题,回车发送(Shift+回车换行)"
                    @keydown.enter.exact.prevent="send()" />
          <el-button v-if="!streaming" type="primary" circle class="send-btn" :disabled="!input.trim()" @click="send()">
            <el-icon :size="16"><Promotion /></el-icon>
          </el-button>
          <el-button v-else circle class="stop-btn" @click="stop()">
            <el-icon :size="15"><VideoPause /></el-icon>
          </el-button>
        </div>
      </div>

      <!-- 追读抽屉 -->
      <el-drawer v-model="drawer" :size="min(560, windowW * 0.9)" :with-header="false" class="ref-drawer">
        <div v-if="current" class="ref-panel">
          <div class="ref-head">
            <div class="ref-head-tag">{{ current.group }} · {{ current.category || '综合' }}</div>
            <h2 class="ref-q">{{ current.question }}</h2>
            <div class="ref-head-meta">{{ current.title }}</div>
          </div>
          <div class="ref-body-scroll">
            <MarkdownView v-if="detail" :content="detail.content" />
          </div>
          <div class="ref-foot">
            <el-button link type="primary" tag="a" :href="current.url" target="_blank">
              查看原站文章 <el-icon><TopRight /></el-icon>
            </el-button>
          </div>
        </div>
      </el-drawer>
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { askStream, chatApi, corpusApi } from '../api/client'
import type { AskRef, ChatMessageRow, ChatSessionRow, ChunkFull } from '../api/types'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Promotion, VideoPause, TopRight, Clock } from '@element-plus/icons-vue'
import MarkdownView from '../components/MarkdownView.vue'
import HistoryRail from '../components/HistoryRail.vue'
import { useMediaQuery } from '../lib/responsive'
import { createSmoothAppender, type SmoothAppender } from '../lib/smoothStream'

interface Msg { role: 'user' | 'assistant'; text: string; refs?: AskRef[]; streaming?: boolean; noAnswer?: boolean }

const topics = [
  'String 为什么不可变?',
  'HashMap 的底层原理?',
  '线程池核心参数有哪些?',
  'JVM 内存区域怎么划分?',
  'Redis 缓存穿透怎么解决?',
  'Spring IoC 是什么?',
]

const messages = ref<Msg[]>([])
const input = ref('')
const streaming = ref(false)
const listEl = ref<HTMLElement | null>(null)
let abort: (() => void) | null = null
// 去抖动渲染器:和 abort 一样提到函数外,stop() 与组件卸载时要能停掉它
let appender: SmoothAppender | null = null

// 历史会话:currentSessionId 为 null 表示"还没建会话",第一条消息发出时才建(不留空会话)
const sessions = ref<ChatSessionRow[]>([])
const currentSessionId = ref<string | null>(null)

const drawer = ref(false)
const current = ref<AskRef | null>(null)
const detail = ref<ChunkFull | null>(null)
const windowW = ref(window.innerWidth)
function min(a: number, b: number) { return a < b ? a : b }

// 窄屏(≤980px)时二级侧栏挪进抽屉,断点与桌面端内嵌 HistoryRail 的隐藏点保持一致
const isNarrow = useMediaQuery('(max-width: 980px)')
const histOpen = ref(false)

/** 抽屉里点某条会话:先收抽屉再加载,加载失败也不把抽屉弹回来 */
function selectFromDrawer(id: string) {
  histOpen.value = false
  void loadSession(id)
}
function newFromDrawer() {
  histOpen.value = false
  newSession()
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
  return m.refs?.length ? `已找到 ${m.refs.length} 篇材料,正在组织回答…` : '检索题库中…'
}

// ── 历史会话 ────────────────────────────────────────────────────────

/** 拉会话列表。每轮问答结束后都调一次——标题和"最后活跃时间"都变了。 */
async function refreshSessions() {
  try {
    sessions.value = await chatApi.sessions()
  } catch {
    // 历史拉不到不该影响问答主流程,这里不打扰用户;真出问题会在下一次操作时暴露
  }
}

/** 后端消息 → 界面消息(字段名不同,且后端省略了 noAnswer=false)。 */
function toMsg(r: ChatMessageRow): Msg {
  return { role: r.role, text: r.content, refs: r.refs, noAnswer: r.noAnswer || undefined }
}

/** 点历史某条:把整段对话读回来,之后的追问接着这个会话走。 */
async function loadSession(id: string) {
  if (streaming.value) return
  try {
    const rows = await chatApi.messages(id)
    messages.value = rows.map(toMsg)
    currentSessionId.value = id
    scrollBottom()
  } catch (e) {
    ElMessage.error('会话加载失败: ' + (e as Error).message)
  }
}

/** 新对话:只清界面并置空 id——真正的会话在发出第一条消息时才建,免得留下空会话。 */
function newSession() {
  if (streaming.value) return
  messages.value = []
  currentSessionId.value = null
}

async function removeSession(s: ChatSessionRow) {
  if (streaming.value) return
  try {
    await ElMessageBox.confirm(`删除「${s.title}」?对话记录会一并清除,不可恢复。`, '删除会话',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch {
    return // 用户点了取消
  }
  try {
    await chatApi.remove(s.id)
    // 删的正好是当前打开的 → 界面也清掉,否则会继续往一个已不存在的会话里写
    if (currentSessionId.value === s.id) {
      messages.value = []
      currentSessionId.value = null
    }
    await refreshSessions()
  } catch (e) {
    ElMessage.error('删除失败: ' + (e as Error).message)
  }
}

// ── 问答 ────────────────────────────────────────────────────────────

async function openRef(r: AskRef) {
  current.value = r
  detail.value = null
  drawer.value = true
  try {
    detail.value = await corpusApi.chunk(r.chunkId)
  } catch (e) {
    ElMessage.error('原文加载失败: ' + (e as Error).message)
  }
}

function scrollBottom() {
  void nextTick(() => {
    listEl.value?.scrollTo({ top: listEl.value.scrollHeight, behavior: 'smooth' })
  })
}

async function send(text?: string) {
  const q = (text ?? input.value).trim()
  if (!q || streaming.value) return
  input.value = ''
  // 取上一轮问答当 history(后端据此把追问里的指代改写成独立问题再检索)
  let history: { q: string; a: string }[] = []
  for (let i = messages.value.length - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m.role === 'assistant' && !m.streaming && m.text) {
      const prev = messages.value[i - 1]
      if (prev && prev.role === 'user') history = [{ q: prev.text, a: m.text }]
      break
    }
  }

  // 本轮是新对话 → 先建会话。拿不到 id 就只影响历史记录,问答继续(和重排降级同一个思路)。
  if (!currentSessionId.value) {
    try {
      currentSessionId.value = (await chatApi.createSession(q)).sessionId
    } catch (e) {
      ElMessage.warning('会话创建失败,本轮问答不会计入历史: ' + (e as Error).message)
    }
  }

  messages.value.push({ role: 'user', text: q })
  messages.value.push({ role: 'assistant', text: '', refs: [], streaming: true })
  // ★ 必须从数组里"读回来"拿到响应式代理。ref([]) 是深层响应式,但 push 进去的是原始对象,
  //   数组只在读取时才把它包成 proxy —— 直接改 push 前的那个局部变量会绕过 set 陷阱,
  //   流式期间一个字都不会渲染,直到 streaming.value=false 触发重渲染才整段刷出来。
  const msg = messages.value[messages.value.length - 1]
  streaming.value = true
  scrollBottom()
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
      appender?.flushNow()   // 出错就别慢慢画了,剩余一次性吐完
      msg.streaming = false
      streaming.value = false
      msg.text = '出错了:' + e
      ElMessage.error(e)
      void refreshSessions() // 提问已经落库了(后端在流开始前就存),列表上有它
    },
  }, history, currentSessionId.value ?? undefined)
}

function stop() {
  abort?.()
  appender?.cancel()   // 停掉 rAF 循环,丢弃还没画出来的字
  const last = messages.value[messages.value.length - 1]
  if (last) last.streaming = false
  streaming.value = false
}

/** 抽屉宽度按视口算,手机横竖屏切换、桌面拉伸窗口时跟着变 */
function onWinResize() {
  windowW.value = window.innerWidth
}

onMounted(() => {
  window.addEventListener('resize', onWinResize)
  void refreshSessions()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onWinResize)
  abort?.()
  appender?.cancel()   // 否则 rAF 回调会继续操作已卸载组件的状态
})
</script>

<style scoped>
.chat-layout { display: flex; gap: 22px; height: 100%; }
.chat-page {
  flex: 1; min-width: 0; max-width: 940px; margin: 0 auto;
  display: flex; flex-direction: column; height: 100%;
}
.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
  padding-bottom: 10px;
}
h1 { margin: 0 0 4px; font-size: 24px; letter-spacing: -0.4px; }
.sub { margin: 0; color: var(--text-dim); font-size: 13.5px; }
/* 窄屏唤出历史会话抽屉的按钮;桌面端历史常驻侧栏,不渲染 */
.hist-toggle {
  flex: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border: 1px solid var(--border-strong);
  border-radius: 999px;
  background: var(--bg-panel);
  color: var(--text-dim);
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--t-fast) var(--ease), color var(--t-fast) var(--ease);
}
.hist-toggle:hover { border-color: var(--accent); color: var(--accent); }

.thread { flex: 1; overflow-y: auto; padding: 8px 2px 16px; display: flex; flex-direction: column; gap: 14px; }
.row { display: flex; }
.row.user { justify-content: flex-end; }
.bubble { max-width: 88%; border-radius: var(--radius); padding: 11px 14px; }
.bubble.user {
  background: linear-gradient(135deg, var(--accent), #15803d);
  color: #04170c;
}
.user-text { margin: 0; white-space: pre-wrap; font-weight: 500; }
.bubble.assistant {
  background: var(--bg-panel);
  border: 1px solid var(--border);
  box-shadow: var(--shadow-panel);
  width: 100%;
}
.cursor {
  display: inline-block; width: 2px; height: 1em; vertical-align: -0.15em;
  background: var(--accent); animation: blink 1s step-start infinite;
}
.status { display: inline-flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text-dim); }
.status-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--accent); animation: breathe 1.4s ease-in-out infinite; }
@keyframes breathe { 0%, 100% { opacity: 0.25; transform: scale(0.8); } 50% { opacity: 1; transform: scale(1); } }
.no-answer {
  margin-top: 8px; display: inline-block; font-size: 11.5px; font-weight: 600;
  color: var(--text-dim); background: var(--bg-soft); border: 1px dashed var(--border-strong);
  border-radius: 7px; padding: 3px 8px;
}
@keyframes blink { 50% { opacity: 0; } }

.refs { margin-top: 12px; border-top: 1px dashed var(--border); padding-top: 10px; }
.refs-title { font-size: 11.5px; color: var(--text-faint); margin-bottom: 7px; }
.refs-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 7px; }
.ref {
  display: flex; gap: 8px; align-items: flex-start; text-align: left;
  border: 1px solid var(--border); background: var(--bg-soft); border-radius: 9px; padding: 7px 9px;
  transition: border-color var(--t-fast) var(--ease), transform var(--t-fast) var(--ease);
}
.ref:hover { border-color: var(--accent); transform: translateY(-1px); }
.ref-idx {
  flex: none; width: 18px; height: 18px; border-radius: 6px; display: grid; place-items: center;
  background: var(--accent-soft); color: var(--accent); font-size: 11px; font-weight: 700; font-family: var(--font-display);
}
.ref-body { display: flex; flex-direction: column; min-width: 0; }
.ref-q { font-size: 12px; color: var(--text); line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.ref-meta { font-size: 10.5px; color: var(--text-faint); margin-top: 2px; }

.composer-wrap { position: sticky; bottom: 0; background: linear-gradient(transparent, var(--bg) 30%); padding-top: 10px; }
.composer {
  display: flex; gap: 8px; align-items: flex-end;
  background: var(--bg-panel); border: 1px solid var(--border-strong); border-radius: 14px; padding: 8px;
  box-shadow: var(--shadow-panel);
}
.composer :deep(.el-textarea__inner) { background: transparent; border: 0; box-shadow: none; color: var(--text); }
.send-btn { background: var(--accent); border: 0; }
.send-btn:hover { background: var(--accent-strong); }
.stop-btn { background: var(--bg-soft); border: 1px solid var(--border); }

.empty { margin: 26px 0; }
.empty-hint { color: var(--text-dim); font-size: 13px; }
.topic-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(230px, 1fr)); gap: 8px; }
.topic {
  text-align: left; padding: 10px 12px; font-size: 13px; color: var(--text);
  background: var(--bg-panel); border: 1px solid var(--border); border-radius: 10px;
  transition: all var(--t-fast) var(--ease);
}
.topic:hover { border-color: var(--accent); background: var(--accent-soft); }

.ref-panel { display: flex; flex-direction: column; height: 100%; }
.ref-head { border-bottom: 1px solid var(--border); padding-bottom: 12px; }
.ref-head-tag { font-size: 11px; color: var(--accent); font-weight: 600; }
.ref-q { margin: 6px 0 4px; font-size: 18px; line-height: 1.4; }
.ref-head-meta { color: var(--text-faint); font-size: 12px; }
.ref-body-scroll { flex: 1; overflow-y: auto; padding: 14px 2px; }
.ref-foot { border-top: 1px solid var(--border); padding-top: 10px; }

@media (max-width: 720px) {
  h1 { font-size: 20px; }
  .sub { display: none; }
  .bubble { max-width: 94%; }
  .topic-grid { grid-template-columns: 1fr; }
}
</style>

<style>
/* el-drawer 把内容传送到 body 外,scoped 样式够不到抽屉体和 HistoryRail,这里用全局样式覆盖:
   撑满抽屉、去掉内嵌时的右边框;删除按钮桌面端靠 hover 显形,手机没有 hover,常显 */
.hist-drawer .el-drawer__body { padding: 12px 14px; }
.hist-drawer .history { width: 100%; border-right: 0; padding-right: 0; }
@media (max-width: 720px) {
  .hist-drawer .hist-del { opacity: 1; }
}
</style>
